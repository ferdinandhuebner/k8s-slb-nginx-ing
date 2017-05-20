package k8sslbnginxing
import java.util
import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.{Actor, ActorRef}
import akka.event.Logging
import io.fabric8.kubernetes.api.model.extensions.Ingress
import io.fabric8.kubernetes.api.model.{ConfigMap, LoadBalancerIngress, Service}
import io.fabric8.kubernetes.client.Watch
import io.fabric8.kubernetes.client.Watcher.Action
import k8sslbnginxing.AppProperties.IngressProperties
import k8sslbnginxing.EventDispatcher.Protocol._
import k8sslbnginxing.KubernetesConversions.{HasMetadataConvenience, ServiceConvenience}
import k8sslbnginxing.KubernetesRepository.{IngressEvent, ServiceEvent}
import k8sslbnginxing.ServiceLoadBalancerController.Protocol.Restart
import k8sslbnginxing.ServiceLoadBalancerController.Reference

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Try

object ServiceLoadBalancerController {
  case object Reference {
    def apply(reference: String): Reference = {
      def split(s: String): (String, String) = {
        val x = s.split("@")
        (x(0), x(1))
      }
      val (name, namespace) = split(reference)
      Reference(name, namespace)
    }
  }
  case class Reference(name: String, namespace: String)

  object Protocol {
    object Restart
  }
}

class ServiceLoadBalancerController(
    k8s: KubernetesRepository,
    pillar: Reference,
    tcpRef: Reference,
    udpRef: Reference,
    eventDispatcher: ActorRef,
    portWhitelistString: String,
    portBlacklistString: String
) extends Actor {

  private val log = Logging(context.system, this)
  private val connectionWasLost = new AtomicBoolean(false)
  private val services: mutable.Map[String, Service] = mutable.Map()
  private val watches: mutable.Buffer[Watch] = mutable.Buffer()
  private val ingress: mutable.Buffer[LoadBalancerIngress] = mutable.Buffer()

  private val whitelist: Set[Int] = IngressProperties.singlePorts(portWhitelistString)
  private val whitelistRange: Set[(Int, Int)] = IngressProperties.portRanges(portWhitelistString)
  private val blacklist: Set[Int] = IngressProperties.singlePorts(portBlacklistString)
  private val blacklistRange: Set[(Int, Int)] = IngressProperties.portRanges(portBlacklistString)

  private def onConnectionLost(e: Throwable): Unit = {
    log.debug("connection lost")
    if (connectionWasLost.compareAndSet(false, true)) {
      watches.foreach(w => Try(w.close()))
      watches.clear()
      self ! Restart
    }
  }

  private def getLoadBalancerIngress(ingress: Ingress): List[LoadBalancerIngress] = {
    if (ingress != null && ingress.getStatus != null && ingress.getStatus.getLoadBalancer != null
        && ingress.getStatus.getLoadBalancer.getIngress != null) {
      ingress.getStatus.getLoadBalancer.getIngress.asScala.toList
    } else {
      Nil
    }
  }

  override def preStart(): Unit = {
    log.debug("starting")
    services.clear()
    watches.foreach(w => Try(w.close()))
    watches.clear()
    ingress.clear()

    services ++= k8s.services().filter(_.isServiceLoadBalancer).map(svc => svc.hashKey -> svc).toMap
    ingress ++= k8s.ingress(pillar.name, pillar.namespace).map(getLoadBalancerIngress).getOrElse(Nil)
    watches ++= Seq(
      k8s.watchIngress(pillar.name, pillar.namespace, evt => self ! evt, e => onConnectionLost(e)),
      k8s.watchServices(evt => self ! evt, e => onConnectionLost(e))
    )

    log.debug(services.size + " services are managed")
    log.debug("pillar ingress: " + ingress)

    services.values.foreach(svc => processService(svc, update = false))
  }

  private def loadTcpMap(): ConfigMap = {
    val map = k8s.configMap(tcpRef.namespace, tcpRef.name)
    if (map.getData == null) {
      map.setData(new util.HashMap())
    }
    map
  }
  private def loadUdpMap(): ConfigMap = {
    val map = k8s.configMap(udpRef.namespace, udpRef.name)
    if (map.getData == null) {
      map.setData(new util.HashMap())
    }
    map
  }

  private def getInvalidPorts(service: Service): List[Int] = {
    service.ports.map(_.getPort.toInt).filter(p => {
      if (blacklist.isEmpty && blacklistRange.isEmpty) {
        if (whitelist.isEmpty && whitelistRange.isEmpty) {
          false
        } else {
          !(whitelist.contains(p) || whitelistRange.exists(range => p >= range._1 && p <= range._2))
        }
      } else {
        if (blacklist.contains(p) || blacklistRange.exists(range => p >= range._1 && p <= range._2)) {
          !(whitelist.contains(p) || whitelistRange.exists(range => p >= range._1 && p <= range._2))
        } else {
          false
        }
      }
    })
  }

  private def getPortConflicts(service: Service): List[(String, String)] = {
    val tcpMap = loadTcpMap()
    val udpMap = loadUdpMap()

    service.ports.map(port => {
      if ("UDP".equals(port.getProtocol) || "udp".equals(port.getProtocol)) {
        val svc = udpMap.getData.asScala.getOrElse(
          port.getPort.toString,
          s"${service.namespace}/${service.name}:$port")
        (s"${port.getPort.toString}/udp", svc)
      } else {
        val svc = tcpMap.getData.asScala.getOrElse(
          port.getPort.toString,
          s"${service.namespace}/${service.name}:$port")
        (s"${port.getPort.toString}/tcp", svc)
      }
    }).filter(elem => !elem._2.startsWith(s"${service.namespace}/${service.name}:"))
  }

  private def bindPorts(service: Service): Unit = {
    val tcpMap = loadTcpMap()
    val udpMap = loadUdpMap()

    service.ports.foreach(port => {
      if ("UDP".equals(port.getProtocol) || "udp".equals(port.getProtocol)) {
        log.debug(s"putting ${service.namespace}/${service.name}:${port.getPort.toString} to udp map")
        udpMap.getData.put(
          port.getPort.toString,
          s"${service.namespace}/${service.name}:${port.getPort.toString}"
        )
      } else {
        log.debug(s"putting ${service.namespace}/${service.name}:${port.getPort.toString} to tcp map")
        tcpMap.getData.put(
          port.getPort.toString,
          s"${service.namespace}/${service.name}:${port.getPort.toString}"
        )
      }
    })
    k8s.replaceDataInConfigMap(tcpMap)
    k8s.replaceDataInConfigMap(udpMap)
  }

  private def unbindPorts(service: Service): Unit = {
    val tcpMap = loadTcpMap()
    val udpMap = loadUdpMap()

    tcpMap.getData.entrySet().asScala.filter(entry =>
      entry.getValue.startsWith(s"${service.namespace}/${service.name}:")
    ).foreach(entry => {
      log.debug("removing " + entry + " from tcp map")
      tcpMap.getData.remove(entry.getKey)
    })

    udpMap.getData.entrySet().asScala.filter(entry =>
      entry.getValue.startsWith(s"${service.namespace}/${service.name}:")
    ).foreach(entry => {
      log.debug("removing " + entry + " from udp map")
      udpMap.getData.remove(entry.getKey)
    })

    k8s.replaceDataInConfigMap(tcpMap)
    k8s.replaceDataInConfigMap(udpMap)
  }

  private def processService(svc: Service, update: Boolean): Unit = {
    log.debug("processing " + svc.asString)
    val invalidPorts = getInvalidPorts(svc)
    if (invalidPorts.isEmpty) {
      val conflicts = getPortConflicts(svc)
      if (conflicts.isEmpty) {
        log.debug("no port conflicts for " + svc.asString)
        bindPorts(svc)
        svc.setLoadBalancerIngress(ingress.toList)
        k8s.updateLoadBalancerStatus(svc)
        eventDispatcher ! (if (update) LoadBalancerUpdated(svc) else LoadBalancerCreated(svc))
      } else {
        log.debug(conflicts.length + " port conflicts for " + svc.asString)
        unbindPorts(svc)
        svc.setLoadBalancerIngress(Nil)
        k8s.updateLoadBalancerStatus(svc)
        eventDispatcher ! PortsInUse(svc, conflicts)
      }
    } else {
      log.debug("invalid ports for " + svc.asString + ": " + invalidPorts)
      unbindPorts(svc)
      svc.setLoadBalancerIngress(Nil)
      k8s.updateLoadBalancerStatus(svc)
      if (update) {
        eventDispatcher ! LoadBalancerDeleted(svc)
      }
      eventDispatcher ! PortsNotAllowed(svc, invalidPorts)
    }
  }

  override def receive: Receive = {
    case Restart => throw new RuntimeException("Restart requested")
    case evt@ServiceEvent(svc, action) =>
      log.debug("Received " + evt)
      action match {
        case Action.ADDED =>
          if (svc.isServiceLoadBalancer) {
            log.debug("new service added: " + svc.asString)
            services += svc.hashKey -> svc
            processService(svc, update = false)
          }
        case Action.MODIFIED =>
          log.debug("service " + svc.asString + " modified")
          if (svc.isServiceLoadBalancer && !services.contains(svc.hashKey)) {
            log.debug("service " + svc.asString + " is now a load balancer")
            services += svc.hashKey -> svc
            processService(svc, update = false)
          } else {
            services.get(svc.hashKey).foreach(oldSvc => {
              log.debug("service " + svc.asString + " is a load balancer")
              if (!svc.isServiceLoadBalancer) {
                log.debug("service " + svc.asString + " is no longer a load balancer")
                services -= svc.hashKey
                unbindPorts(oldSvc)
                svc.setLoadBalancerIngress(Nil)
                k8s.updateLoadBalancerStatus(svc)
                eventDispatcher ! LoadBalancerDeleted(svc)
              } else {
                services += svc.hashKey -> svc
                if (svc.ports != oldSvc.ports) {
                  log.debug("service " + svc.asString + " has relevant change")
                  processService(svc, update = true)
                } else {
                  log.debug("service " + svc.asString + " has no relevant change")
                }
              }
            })
          }
        case Action.DELETED =>
          log.debug("service " + svc.asString + " has been deleted")
          services.get(svc.hashKey).foreach(oldSvc => {
            services -= svc.hashKey
            unbindPorts(oldSvc)
          })
        case _ =>
          log.warning("Unable to process event " + evt)
      }
    case evt@IngressEvent(ing, action) =>
      log.debug("Received " + evt)
      if (action == Action.ADDED || action == Action.MODIFIED) {
        val newIng = getLoadBalancerIngress(ing)
        if (newIng != ingress) {
          log.debug("load balancer needs updating")
          ingress.clear()
          ingress ++= newIng
          services.values.foreach(svc => {
            svc.setLoadBalancerIngress(ingress.toList)
            k8s.updateLoadBalancerStatus(svc)
            eventDispatcher ! LoadBalancerUpdated(svc)
          })
        }
      } else if (action == Action.DELETED) {
        ingress.clear()
        services.values.foreach(svc => {
          svc.setLoadBalancerIngress(Nil)
          k8s.updateLoadBalancerStatus(svc)
          eventDispatcher ! LoadBalancerUpdated(svc)
        })
      } else {
        log.warning("Unable to process event " + evt)
      }
  }
}
