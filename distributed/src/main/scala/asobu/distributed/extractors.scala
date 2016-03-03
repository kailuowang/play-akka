package asobu.distributed

import asobu.distributed.Action.DistributedRequest
import asobu.distributed.Extractors._
import asobu.dsl._
import asobu.dsl.extractors.JsonBodyExtractor
import asobu.dsl.util.HListOps.{CombineTo, RestOf2}
import cats.{Monad, Functor, Eval}
import cats.sequence.RecordSequencer
import shapeless.ops.hlist.Prepend
import asobu.dsl.util.RecordOps.{FieldKV, FieldKVs}
import cats.data.{Xor, Kleisli}
import play.api.libs.json.{Reads, Json}
import play.core.routing.RouteParams
import shapeless.labelled.FieldType
import shapeless.ops.hlist.Mapper
import shapeless._, labelled.field
import ExtractResult._
import cats.syntax.all._
import SerializableCatsInstances._
import play.api.mvc._, play.api.mvc.Results._

import scala.annotation.implicitNotFound
import scala.concurrent.Future

trait Extractors[TMessage] {
  /**
   * List to be extracted from request and send to the remote handler
   */
  type LToSend <: HList
  type LParam <: HList
  type LExtra <: HList

  val remoteExtractorDef: RemoteExtractorDef[LToSend, LParam, LExtra]

  def localExtract(dr: DistributedRequest[LToSend]): ExtractResult[TMessage]
}

object Extractors {
  type RouteParamsExtractor[T] = Extractor[RouteParams, T]
  /**
   * Extract information at the gateway end
   */
  type RemoteExtractor[T] = Extractor[(RouteParams, Request[AnyContent]), T]

  type BodyExtractor[T] = Extractor[AnyContent, T]

  class builder[TMessage] {

    def apply[LExtracted <: HList, LParamExtracted <: HList, LRemoteExtra <: HList, LBody <: HList, TRepr <: HList](
      remoteRequestExtractorDefs: RequestExtractorDefinition[LRemoteExtra],
      bodyExtractor: BodyExtractor[LBody]
    )(implicit
      gen: LabelledGeneric.Aux[TMessage, TRepr],
      r: RestOf2.Aux[TRepr, LRemoteExtra, LBody, LParamExtracted],
      prepend: Prepend.Aux[LParamExtracted, LRemoteExtra, LExtracted],
      combineTo: CombineTo[LExtracted, LBody, TRepr],
      rpeb: RouteParamsExtractorBuilder[LParamExtracted]): Extractors[TMessage] = new Extractors[TMessage] {

      type LToSend = LExtracted
      type LParam = LParamExtracted
      type LExtra = LRemoteExtra
      import LocalExecutionContext.instance
      val remoteExtractorDef = RemoteExtractorDef(rpeb, remoteRequestExtractorDefs)

      def localExtract(dr: DistributedRequest[LToSend]): ExtractResult[TMessage] = {
        import scala.concurrent.ExecutionContext.Implicits.global
        bodyExtractor.run(dr.body).map { body ⇒
          val repr = combineTo(dr.extracted, body)
          gen.from(repr)
        }
      }
    }
  }
  def build[TMessage] = new builder[TMessage]
}

case class RemoteExtractorDef[LExtracted <: HList, LParamExtracted <: HList, LRemoteExtra <: HList](
    routeParamsExtractorBuilder: RouteParamsExtractorBuilder[LParamExtracted],
    requestExtractorDefinition: RequestExtractorDefinition[LRemoteExtra]
)(implicit val prepend: Prepend.Aux[LParamExtracted, LRemoteExtra, LExtracted]) {

  lazy val extractor: RemoteExtractor[LExtracted] =
    Extractor.zip(routeParamsExtractorBuilder(), requestExtractorDefinition.apply())

}

object BodyExtractor {
  val empty = Extractor.empty[AnyContent]
  def json[T: Reads]: BodyExtractor[T] = Extractor.fromFunction(JsonBodyExtractor.extractBody[T])
  def jsonList[T: Reads](implicit lgen: LabelledGeneric[T]): BodyExtractor[lgen.Repr] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    json[T] map (lgen.to(_))
  }

}

object RemoteExtractor {
  val empty = Extractor.empty[(RouteParams, Request[AnyContent])]
}

object RouteParamsExtractor {
  def apply[L <: HList](implicit builder: RouteParamsExtractorBuilder[L]): RouteParamsExtractor[L] = builder()
}

@implicitNotFound("Cannot construct RouteParamsExtractor out of ${L}")
trait RouteParamsExtractorBuilder[L <: HList] extends (() ⇒ RouteParamsExtractor[L]) with Serializable

trait MkRouteParamsExtractorBuilder0 {

  //todo: this extract from either path or query without a way to specify one way or another.
  object kvToKlesili extends Poly1 {
    implicit def caseKV[K <: Symbol, V: RouteParamRead](
      implicit
      w: Witness.Aux[K]
    ): Case.Aux[FieldKV[K, V], FieldType[K, Kleisli[ExtractResult, RouteParams, V]]] =
      at[FieldKV[K, V]] { kv ⇒
        field[K](Kleisli { (params: RouteParams) ⇒
          val field: String = w.value.name
          val pe = RouteParamRead[V]
          val extracted = pe(field, params)
          fromXor(extracted.leftMap(m ⇒ BadRequest(Json.obj("error" → s"missing field $field $m"))))
        })
      }
  }

  implicit def autoMkForRecord[Repr <: HList, KVs <: HList, KleisliRepr <: HList](
    implicit
    ks: FieldKVs.Aux[Repr, KVs],
    mapper: Mapper.Aux[kvToKlesili.type, KVs, KleisliRepr],
    sequence: RecordSequencer[KleisliRepr]
  ): RouteParamsExtractorBuilder[Repr] = new RouteParamsExtractorBuilder[Repr] {
    def apply(): RouteParamsExtractor[Repr] =
      sequence(ks().map(kvToKlesili)).asInstanceOf[Kleisli[ExtractResult, RouteParams, Repr]] //this cast is needed because a RecordSequencer.Aux can't find the Repr if Repr is not explicitly defined. The cast is safe because the mapper guarantee the result type. todo: find a way to get rid of the cast
  }

}

object RouteParamsExtractorBuilder extends MkRouteParamsExtractorBuilder0 {

  def apply[T <: HList](implicit rpe: RouteParamsExtractorBuilder[T]): RouteParamsExtractorBuilder[T] = rpe

  implicit val empty: RouteParamsExtractorBuilder[HNil] = new RouteParamsExtractorBuilder[HNil] {
    def apply() = Extractor.empty[RouteParams]
  }
}

trait RouteParamRead[V] extends Serializable {
  def apply(field: String, params: RouteParams): Xor[String, V]
}

//This whole thing is needed because PathBindable and QueryStringBindable isn't serializable
object RouteParamRead {

  def apply[V](implicit rpe: RouteParamRead[V]) = rpe

  def mk[V](f: ⇒ (String, RouteParams) ⇒ Xor[String, V]) = new RouteParamRead[V] {
    def apply(field: String, params: RouteParams): Xor[String, V] = {
      f(field, params)
    }
  }

  implicit val rpeInt: RouteParamRead[Int] = mk(ext[Int])
  implicit val rpeLong: RouteParamRead[Long] = mk(ext[Long])
  implicit val rpeDouble: RouteParamRead[Double] = mk(ext[Double])
  implicit val rpeFloat: RouteParamRead[Float] = mk(ext[Float])
  implicit val rpeBoolean: RouteParamRead[Boolean] = mk(ext[Boolean])
  implicit val rpeString: RouteParamRead[String] = mk(ext[String])

  def ext[V: PathBindable: QueryStringBindable] = (field: String, params: RouteParams) ⇒ {
    params.fromPath[V](field).value.toXor orElse params.fromQuery[V](field).value.toXor
  }
}
