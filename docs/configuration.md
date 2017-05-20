# Configuration

The service load-balancer controller is built on top of Spring Boot. You can configure it through an   
`application.properties` file, environment variables, java system properties or command-line arguments:

* Place the configuration in a file `application.properties` and start the application with 
  `--spring.config.location=/path/to/application.properties`
* The format of an environment variable for `my-flag` is `MY_FLAG`
* The format of a java system property for `my-flag` is `-DmyFlag` or `-Dmy-flag`
* The format of a command-line argument for `my-flag` is `--myFlag` or `--my-flat`

# Configuration flags

## Kubernetes related flags

The controller uses [fabric8's kubernetes client](https://github.com/fabric8io/kubernetes-client) for
kubernetes communication. You can configure it with system properties or environment variables, please
consult the fabric8 documentation for details. 

If you're running the controller in-cluster, the service account credentials are picked up by default
and you don't have to explicitly configure the client. 

## Ingress and Service-Load Balancing related flags

### ingress.pillar

- An existing ingress resource that acts as a pillar for IP addresses that the nginx ingress 
  controller can be reached at from the outside. Those IP addresses will be transferred to 
  services in the `loadBalancer` field.
- default: `none`
- required
- example: `prometheus-ingress@default`
- env variable: INGRESS_PILLAR

### ingress.tcp-config-map

- The ConfigMap for TCP services used by the nginx ingress controller in the form `name@namespace`
- default: `none`
- required
- example: `nginx-tcp-ingress-configmap@kube-system`
- env variable: INGRESS_TCP_CONFIG_MAP

### ingress.udp-config-map

- The ConfigMap for UDP services used by the nginx ingress controller in the form `name@namespace`
- default: `none`
- required
- example: `nginx-udp-ingress-configmap@kube-system`
- env variable: INGRESS_UDP_CONFIG_MAP

### ingress.port-blacklist

- Ports that can't be used for services. If a service uses one of these ports, an event will get
  emitted. The default-blacklist includes ports from nginx, healthz ports by kubernetes components
  and the default NodePort range.
- default: `80,442,443,0-1024,10240-10260,18080,30000-32767`
- example: `80,442,443,0-1024,10240-10260,18080,30000-32767`
- env variable: INGRESS_PORT_BLACKLIST

### ingress.port-whitelist

- Ports that can be used for services. The behaviour is:
  - if the blacklist is empty, only whitelisted ports can be used
  - if the blacklist is not empty, whitelisted ports override blacklisted ports, i.e. if port-range
    `0-1024` is blacklisted but port `22` is whitelisted, port `22` can be used
- default: `empty`
- example: `22,50000-55555`
- env variable: INGRESS_PORT_WHITELISTE