package k8sslbnginxing
import com.fasterxml.jackson.databind.ObjectMapper
import io.fabric8.kubernetes.api.model.extensions.Ingress
import io.fabric8.kubernetes.api.model.{ConfigMap, HasMetadata, Service}
import io.fabric8.kubernetes.client._
import k8sslbnginxing.KubernetesRepository.{ConfigMapEvent, IngressEvent, KubernetesEvent, ServiceEvent}
import okhttp3.{MediaType, Request, RequestBody}

object KubernetesRepository {
  private[k8sslbnginxing] sealed trait KubernetesEvent[+T <: HasMetadata] {
    def resource: T
    def action: Watcher.Action

    override def toString: String = {
      import KubernetesConversions.HasMetadataConvenience
      s"${resource.getKind} ${resource.name}@${resource.namespace} $action"
    }
  }

  private[k8sslbnginxing] case class ServiceEvent(resource: Service, action: Watcher.Action)
      extends KubernetesEvent[Service]
  private[k8sslbnginxing] case class IngressEvent(resource: Ingress, action: Watcher.Action)
      extends KubernetesEvent[Ingress]
  private[k8sslbnginxing] case class ConfigMapEvent(resource: ConfigMap, action: Watcher.Action)
      extends KubernetesEvent[ConfigMap]

  private[k8sslbnginxing] object Protocol {
    object Initialize
  }
}

trait KubernetesRepository {

  def updateLoadBalancerStatus(service: Service): Unit

  def configMap(name: String, namespace: String): ConfigMap
  def replaceDataInConfigMap(configMap: ConfigMap): ConfigMap
  def services(): List[Service]
  def ingresses(): List[Ingress]
  def ingress(name: String, namespace: String): Option[Ingress]
  def watchConfigMap(name: String, namespace: String, watcher: KubernetesEvent[ConfigMap] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch
  def watchServices(watcher: KubernetesEvent[Service] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch
  def watchIngresses(watcher: KubernetesEvent[Ingress] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch
  def watchIngress(name: String, namespace: String, watcher: KubernetesEvent[Ingress] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch
}

class KubernetesRepositoryImpl(private val kubernetesClient: KubernetesClient with HttpClientAware)
    extends KubernetesRepository {

  import scala.collection.JavaConverters._

  private val PatchMediaType = MediaType.parse("application/strategic-merge-patch+json")
  private val mapper = new ObjectMapper()

  override def updateLoadBalancerStatus(service: Service): Unit = {
    import k8sslbnginxing.KubernetesConversions.HasMetadataConvenience
    val statusString = "{\"status\": " + mapper.writeValueAsString(service.getStatus) + "}"
    val requestBody = RequestBody.create(PatchMediaType, statusString)
    val masterUrl = if (kubernetesClient.getMasterUrl.toString.endsWith("/")) {
      kubernetesClient.getMasterUrl.toString.init
    } else {
      kubernetesClient.getMasterUrl.toString
    }
    val request = new Request.Builder().patch(requestBody)
        .url(s"$masterUrl/api/v1/namespaces/${service.namespace}/services/${service.name}/status")
        .build()

    val response = kubernetesClient.getHttpClient.newCall(request).execute()
    val responseCode = response.code()
    response.close()
    if (responseCode != 200) {
      throw new IllegalStateException("Unable to update load balancer status; received HTTP " + responseCode)
    }
  }

  override def configMap(name: String, namespace: String): ConfigMap = {
    kubernetesClient.configMaps().inNamespace(namespace).withName(name).get()
  }

  override def replaceDataInConfigMap(configMap: ConfigMap): ConfigMap = {
    import KubernetesConversions.HasMetadataConvenience
    kubernetesClient.configMaps().inNamespace(configMap.namespace).withName(configMap.name)
        .edit().withData(configMap.getData).done()
  }

  def services(): List[Service] = {
    iterableAsScalaIterable(
      kubernetesClient.services().inAnyNamespace().list().getItems
    ).toList
  }

  def ingresses(): List[Ingress] = {
    iterableAsScalaIterable(
      kubernetesClient.extensions().ingresses().inAnyNamespace().list().getItems
    ).toList
  }

  override def ingress(name: String, namespace: String): Option[Ingress] = {
    Option(
      kubernetesClient.extensions().ingresses().inNamespace(namespace).withName(name).get()
    )
  }

  override def watchConfigMap(name: String, namespace: String,
      watcher: (KubernetesEvent[ConfigMap]) => Unit,
      onCloseFunction: (KubernetesClientException) => Unit): Watch = {

    kubernetesClient.configMaps().inNamespace(namespace).withName(name).watch(new Watcher[ConfigMap] {
      override def onClose(cause: KubernetesClientException): Unit = {
        onCloseFunction.apply(cause)
      }
      override def eventReceived(action: Watcher.Action, resource: ConfigMap): Unit = {
        watcher.apply(ConfigMapEvent(resource, action))
      }
    })
  }

  def watchServices(watcher: KubernetesEvent[Service] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch = {

    kubernetesClient.services().inAnyNamespace().watch(new Watcher[Service] {
      override def onClose(cause: KubernetesClientException): Unit = {
        onCloseFunction.apply(cause)
      }
      override def eventReceived(action: Watcher.Action, resource: Service): Unit = {
        watcher.apply(ServiceEvent(resource, action))
      }
    })
  }

  override def watchIngress(name: String, namespace: String, watcher: (KubernetesEvent[Ingress]) => Unit, onCloseFunction: (KubernetesClientException) => Unit): Watch = {
    kubernetesClient.extensions().ingresses().inNamespace(namespace).withName(name).watch(new Watcher[Ingress] {
      override def onClose(cause: KubernetesClientException): Unit = {
        onCloseFunction.apply(cause)
      }
      override def eventReceived(action: Watcher.Action, resource: Ingress): Unit = {
        watcher.apply(IngressEvent(resource, action))
      }
    })
  }

  def watchIngresses(watcher: KubernetesEvent[Ingress] => Unit, onCloseFunction: KubernetesClientException => Unit): Watch = {
    kubernetesClient.extensions().ingresses().inAnyNamespace().watch(new Watcher[Ingress] {
      override def onClose(cause: KubernetesClientException): Unit = {
        onCloseFunction.apply(cause)
      }
      override def eventReceived(action: Watcher.Action, resource: Ingress): Unit = {
        watcher.apply(IngressEvent(resource, action))
      }
    })
  }
}
