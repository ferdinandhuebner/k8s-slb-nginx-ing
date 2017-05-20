package k8sslbnginxing
import io.fabric8.kubernetes.api.model._
import io.fabric8.kubernetes.api.model.extensions.Ingress

import scala.collection.JavaConverters._
import scala.collection.mutable

object KubernetesConversions {

  implicit class ServiceConvenience(val self: Service) extends AnyVal {

    def ports: List[ServicePort] = {
      if (self.getSpec != null && self.getSpec.getPorts != null) {
        self.getSpec.getPorts.asScala.toList
      } else {
        Nil
      }
    }

    def setLoadBalancerIngress(ingress: List[LoadBalancerIngress]): Unit = {
      if (self.getSpec != null) {
        if (self.getStatus == null) {
          self.setStatus(
            new ServiceStatusBuilder().withNewLoadBalancer()
                .addAllToIngress(ingress.asJava)
                .endLoadBalancer().build()
          )
        } else {
          if (self.getStatus.getLoadBalancer == null) {
            self.getStatus.setLoadBalancer(
              new LoadBalancerStatusBuilder()
                  .addAllToIngress(ingress.asJava)
                  .build()
            )
          } else {
            self.getStatus.getLoadBalancer.setIngress(ingress.asJava)
          }
        }
      }
    }
  }

  implicit class HasMetadataConvenience(val self: HasMetadata) extends AnyVal {
    def name: String = {
      if (self != null && self.getMetadata != null) {
        self.getMetadata.getName
      } else {
        null
      }
    }
    def namespace: String = {
      if (self != null && self.getMetadata != null) {
        self.getMetadata.getNamespace
      } else {
        null
      }
    }

    def annotation(name: String): String = {
      if (self != null && self.getMetadata != null && self.getMetadata.getAnnotations != null) {
        self.getMetadata.getAnnotations.get(name)
      } else {
        null
      }
    }

    def hashKey: String = s"${self.getKind}/$name@$namespace"

    def asString: String = s"${self.getKind} ${self.name}@${self.namespace}"

    def isServiceLoadBalancer: Boolean = {
      self match {
        case svc: Service =>
          svc.getSpec != null && "LoadBalancer".equals(svc.getSpec.getType)
        case _ => false
      }
    }

    def loadBalancerIngress: Set[String] = {
      def toSet(lbIngress: java.util.List[LoadBalancerIngress]): Set[String] = {
        lbIngress.asScala.map(ing => {
          if (ing.getHostname != null) {
            ing.getHostname
          } else {
            ing.getIp
          }
        }
        ).filter(_ != null).toSet
      }

      self match {
        case svc: Service =>
          if (svc.getStatus != null && svc.getStatus.getLoadBalancer != null
              && svc.getStatus.getLoadBalancer.getIngress != null) {
            toSet(svc.getStatus.getLoadBalancer.getIngress)
          } else {
            Set.empty
          }
        case ing: Ingress =>
          if (ing.getStatus != null && ing.getStatus.getLoadBalancer != null
              && ing.getStatus.getLoadBalancer.getIngress != null) {
            toSet(ing.getStatus.getLoadBalancer.getIngress)
          } else {
            Set.empty
          }
        case _ => Set.empty
      }
    }
  }

}
