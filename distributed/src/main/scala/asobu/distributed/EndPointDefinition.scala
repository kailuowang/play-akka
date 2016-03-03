package asobu.distributed

import akka.actor.{ActorRef, ActorSelection}
import asobu.distributed.Endpoint.Prefix
import asobu.distributed.Extractors.RemoteExtractor
import asobu.dsl.{Extractor, RequestExtractor, ExtractResult}
import asobu.dsl.util.HListOps.CombineTo
import play.api.mvc.{AnyContent, Request}
import play.core.routing.RouteParams
import play.routes.compiler.Route
import shapeless.{HNil, HList}
import cats.implicits._
import shapeless.ops.hlist.Prepend

/**
 * Endpoint definition by the remote handler
 */
trait EndpointDefinition {
  /**
   * type of the Message
   */
  type T
  val routeInfo: Route
  val prefix: Prefix
  def extract(routeParams: RouteParams, request: Request[AnyContent]): ExtractResult[T]
  def handlerActor: ActorRef
}

object EndpointDefinition {
  type Aux[T0] = EndpointDefinition { type T = T0 }
}

case class EndPointDefImpl[LExtracted <: HList, LParam <: HList, LExtra <: HList](
    prefix: Prefix,
    routeInfo: Route,
    remoteExtractorDef: RemoteExtractorDef[LExtracted, LParam, LExtra],
    handlerActor: ActorRef
) extends EndpointDefinition {

  type T = LExtracted
  lazy implicit val prepend = remoteExtractorDef.prepend
  def extract(routeParams: RouteParams, request: Request[AnyContent]): ExtractResult[LExtracted] = {
    remoteExtractorDef.extractor.run((routeParams, request))
  }
}

/**
 * Endpoint that takes no input at all, just match a route path
 */
case class NullaryEndpointDefinition(
    prefix: Prefix,
    routeInfo: Route,
    handlerActor: ActorRef
) extends EndpointDefinition {

  type T = HNil
  def extract(routeParams: RouteParams, request: Request[AnyContent]): ExtractResult[T] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    ExtractResult.pure(HNil)
  }
}
