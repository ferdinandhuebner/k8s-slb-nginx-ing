package k8sslbnginxing

import java.util.Properties
import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Props}
import com.typesafe.config.ConfigFactory
import io.fabric8.kubernetes.client.{DefaultKubernetesClient, HttpClientAware, KubernetesClient}
import k8sslbnginxing.AppProperties.IngressProperties
import k8sslbnginxing.ServiceLoadBalancerController.Reference
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

object App {

  def main(args: Array[String]): Unit = {
    val app = new SpringApplication(classOf[AppConfig])
    app.setDefaultProperties(loadDefaultProperties())
    app.run(args: _*)
  }

  private def loadDefaultProperties(): Properties = {
    val props = new Properties()
    val stream = getClass.getResourceAsStream("/app-defaults.properties")
    try {
      props.load(stream)
      props
    } finally {
      stream.close()
    }
  }

  @SpringBootApplication
  @EnableConfigurationProperties(Array(classOf[IngressProperties]): _*)
  class AppConfig {
    @Bean
    def kubernetesClient(): KubernetesClient with HttpClientAware = {
      val client = new DefaultKubernetesClient()

      try {
        client.services().inNamespace("default").withName("kubernetes").get()
      } catch {
        case e: Exception =>
          throw new IllegalStateException("Unable to connect to kubernetes", e)
      }

      client
    }

    @Autowired
    var ingressProperties: IngressProperties = _

    @Bean
    def kubernetesRepository(): KubernetesRepository = {
      new KubernetesRepositoryImpl(kubernetesClient())
    }

    private def validateIngressProperties(): Unit = {
      val k8s = kubernetesRepository()
      val pillarRef = Reference(ingressProperties.pillar)
      if (k8s.ingress(name = pillarRef.name, namespace = pillarRef.namespace) == null) {
        throw new IllegalArgumentException(s"ingress pillar ${ingressProperties.pillar} not found")
      }

      val tcpRef = Reference(ingressProperties.tcpConfigMap)
      if (k8s.configMap(name = tcpRef.name, namespace = tcpRef.namespace) == null) {
        throw new IllegalArgumentException(s"tcp configmap ${ingressProperties.tcpConfigMap} not found")
      }

      val udpRef = Reference(ingressProperties.udpConfigMap)
      if (k8s.configMap(name = udpRef.name, namespace = udpRef.namespace) == null) {
        throw new IllegalArgumentException(s"tcp configmap ${ingressProperties.udpConfigMap} not found")
      }

      IngressProperties.singlePorts(ingressProperties.portWhitelist)
      IngressProperties.portRanges(ingressProperties.portWhitelist)
      IngressProperties.singlePorts(ingressProperties.portBlacklist)
      IngressProperties.portRanges(ingressProperties.portBlacklist)
    }

    @Bean
    def serviceLoadBalancer(): ActorRef = {
      validateIngressProperties()
      val pillarRef = Reference(ingressProperties.pillar)
      val tcpRef = Reference(ingressProperties.tcpConfigMap)
      val udpRef = Reference(ingressProperties.udpConfigMap)

      actorSystem().actorOf(Props(new ServiceLoadBalancerController(
        k8s = kubernetesRepository(),
        pillar = pillarRef,
        tcpRef = tcpRef,
        udpRef = udpRef,
        eventDispatcher = eventDispatcher(),
        portWhitelistString = ingressProperties.portWhitelist,
        portBlacklistString = ingressProperties.portBlacklist
      )))
    }

    @Bean
    def eventDispatcher(): ActorRef = {
      actorSystem().actorOf(EventDispatcher.props(kubernetesClient()), "event-dispatcher")
    }

    trait SpringActorSystemAdapter {
      def actorSystem(): ActorSystem
      def shutdown(): Unit = {
        Await.result(actorSystem().terminate(), FiniteDuration(30, TimeUnit.SECONDS))
      }
    }

    @Bean(destroyMethod = "shutdown")
    def actorSystemAdapter(): SpringActorSystemAdapter = {
      new SpringActorSystemAdapter {
        private val akkaConfig = ConfigFactory.parseString(
          """
            |akka {
            |  loggers = ["akka.event.slf4j.Slf4jLogger"]
            |  loglevel = "DEBUG"
            |  logging-filter = "akka.event.slf4j.Slf4jLoggingFilter"
            |}
          """.stripMargin)

        private val system = ActorSystem.create("k8s-dns-sky", akkaConfig)
        override def actorSystem(): ActorSystem = system
      }
    }

    @Bean
    def actorSystem(): ActorSystem = actorSystemAdapter().actorSystem()
  }

}
