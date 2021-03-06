package hydra.kafka.model

import akka.http.rest.hal.{Link, ResourceBuilder}
import hydra.core.marshallers.HydraJsonSupport
import spray.json._


/**
  * Created by alexsilva on 3/30/17.
  */
trait TopicMetadataAdapter extends HydraJsonSupport {

  implicit val topicMetadataFormat = jsonFormat10(TopicMetadata)

  def streamLink(rel: String, id: String) = rel -> Link(href = s"/streams/$id")

  def toResource(tm: TopicMetadata): JsValue = ResourceBuilder(
    withData = Some(tm.toJson),
    withLinks = Some(Map(streamLink("self", tm.id.toString), schemaLink(tm.subject)))).build
}
