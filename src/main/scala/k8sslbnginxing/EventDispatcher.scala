package k8sslbnginxing
import java.time.OffsetDateTime

import akka.actor.{Actor, Props}
import akka.event.Logging
import io.fabric8.kubernetes.api.model.HasMetadata
import io.fabric8.kubernetes.client.KubernetesClient
import k8sslbnginxing.EventDispatcher.Protocol._
import k8sslbnginxing.KubernetesConversions.HasMetadataConvenience

object EventDispatcher {
  object Protocol {
    sealed trait Event
    case class PortsInUse(resource: HasMetadata, conflicts: List[(String, String)]) extends Event
    case class PortsNotAllowed(resource: HasMetadata, ports: List[Int]) extends Event
    case class CannotUpdateLoadBalancerStatus(resource: HasMetadata, cause: String) extends Event
    case class LoadBalancerCreated(resource: HasMetadata) extends Event
    case class LoadBalancerUpdated(resource: HasMetadata) extends Event
    case class LoadBalancerDeleted(resource: HasMetadata) extends Event
    case class CannotBindPorts(resource: HasMetadata, cause: String) extends Event
    case class CannotUnbindPorts(resource: HasMetadata, cause: String) extends Event
  }

  def props(k8s: KubernetesClient): Props = Props(new EventDispatcher(k8s))
}

class EventDispatcher(private val k8s: KubernetesClient) extends Actor {

  val log = Logging(context.system, this)

  private def fireEvent(namespace: String, name: String, evtType: String, reason: String,
      message: String, involvedObject: HasMetadata): Unit = {
    log.debug("Firing " + message)
    try {
      val event = k8s.events().inNamespace(namespace).withName(name).get()
      k8s.events().inNamespace(namespace).withName(name).edit()
          .withCount(event.getCount + 1).withLastTimestamp(OffsetDateTime.now().toString)
          .done()
    } catch {
      case _: Exception =>
        try {
          k8s.events().inNamespace(namespace).createNew()
              .withNewMetadata().withNamespace(namespace).withName(name).endMetadata()
              .withNewInvolvedObject()
              .withApiVersion(involvedObject.getApiVersion).withKind(involvedObject.getKind)
              .withNamespace(involvedObject.namespace).withName(involvedObject.name).withUid(involvedObject.getMetadata.getUid)
              .endInvolvedObject()
              .withNewSource().withComponent("loadbalanacer-controller").endSource()
              .withType(evtType)
              .withReason(reason)
              .withMessage(message)
              .withFirstTimestamp(OffsetDateTime.now().toString)
              .withLastTimestamp(OffsetDateTime.now().toString)
              .withCount(1)
              .done()
        } catch {
          case e: Exception => log.error(e, "Unable to put event")
        }
    }
  }

  private def truncate(s: String): String = {
    if (s.length > 253) {
      s.substring(0, 253)
    } else {
      s
    }
  }

  override def receive: Receive = {
    case evt: Event => evt match {
      case PortsInUse(resource, conflicts) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.slb.portconflict"
        val conflictsMsg = conflicts.map(x => s"${x._1} is in use by ${x._2}").mkString(", ")
        val message = s"Not all ports can be bound: $conflictsMsg"
        fireEvent(resource.namespace, truncate(eventName), "Warning", "PortsInUse", message, resource)
      case PortsNotAllowed(resource, ports) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.slb.portnotallowed"
        val message = s"The following ports can't be used: ${ports.mkString(", ")}"
        fireEvent(resource.namespace, truncate(eventName), "Warning", "PortsNotAllowed", message, resource)
      case CannotUpdateLoadBalancerStatus(resource, cause) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.slb.updatefailed"
        val message = s"Cannot update load balancer: $cause"
        fireEvent(resource.namespace, truncate(eventName), "Warning", "UpdateFailed", message, resource)
      case CannotBindPorts(resource, cause) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.slb.cannotbind"
        val message = s"Cannot bind load balancer ports: $cause"
        fireEvent(resource.namespace, truncate(eventName), "Warning", "CantBind", message, resource)
      case CannotUnbindPorts(resource, cause) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.slb.cannotunbind"
        val message = s"Cannot unbind load balancer ports: $cause"
        fireEvent(resource.namespace, truncate(eventName), "Warning", "CantUnbind", message, resource)
      case LoadBalancerCreated(resource) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.slb.created"
        val message = s"Load balancer created"
        fireEvent(resource.namespace, truncate(eventName), "Normal", "Created", message, resource)
      case LoadBalancerUpdated(resource) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.slb.updated"
        val message = s"Load balancer updated"
        fireEvent(resource.namespace, truncate(eventName), "Normal", "Updated", message, resource)
      case LoadBalancerDeleted(resource) =>
        val eventName = s"${resource.name}.${resource.getMetadata.getUid}.slb.deleted"
        val message = s"Load balancer deleted"
        fireEvent(resource.namespace, truncate(eventName), "Normal", "Deleted", message, resource)
    }
    case x =>
      log.warning("Unhandled message: " + x)
      unhandled(x)
  }
}
