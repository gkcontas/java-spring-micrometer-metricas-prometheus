# Métricas com Micrometer, Prometheus e Grafana

Uma API de pedidos instrumentada de ponta a ponta: os quatro tipos primitivos de métrica, cada um num caso em que é o certo; `docker compose up` subindo aplicação, Prometheus e Grafana com dashboard de 12 painéis já provisionado; e as armadilhas que transformam observabilidade em ruído caro — todas **reproduzidas e medidas**, não descritas.

## Status

✅ Implementado, testado e validado manualmente com a stack completa no ar. 34 testes, build limpo.

## Stack

- Java 21 + Spring Boot 3.5 + Actuator
- Micrometer + `micrometer-registry-prometheus` (OpenMetrics/Prometheus)
- Spring AOP (`@Timed`, `@Counted`)
- PostgreSQL 16 + JPA + Flyway
- Prometheus 2.53 + Grafana 11.1 via `docker-compose`
- Gradle (Kotlin DSL) + wrapper `gradlew`
- Lombok na entidade; DTOs como records
- JUnit 5 + `SimpleMeterRegistry` + Testcontainers

## Os quatro tipos, e por que escolher errado mente

Escolher o tipo errado não falha. Produz um gráfico que diz, em silêncio, algo que não é verdade.

| Tipo | Métrica aqui | A regra |
|---|---|---|
| **Counter** | `orders.accepted{channel,status}` | Só cresce. O Prometheus entende que uma queda é restart. Sempre consultar com `rate()` — o valor absoluto ("42819 pedidos desde o deploy") não responde pergunta nenhuma. |
| **Gauge** | `orders.pending` | Leitura instantânea, amostrada no scrape. **Não** serve para contar eventos: tudo que acontece entre dois scrapes é invisível, e um restart zera sem deixar rastro. |
| **Timer** | `orders.processing{outcome}` | Contagem, tempo total e distribuição de durações num meter só. |
| **DistributionSummary** | `orders.amount{channel}` | O mesmo, para grandeza que não é tempo. Mil pedidos de um real e um pedido de mil reais somam igual; só a distribuição os separa. |

Métrica de negócio é a metade que ninguém entrega pronta. Heap, threads, GC, pool de conexões e latência HTTP chegam de graça com o Actuator e respondem **uma** pergunta: o processo está vivo. Nenhuma delas responde se a empresa está ganhando dinheiro. `NoOrdersAccepted` é um incidente que um dashboard de JVM verde não mostra.

## As oito armadilhas, todas medidas

### 1. `@Timed` que não mede nada

No Spring Boot 3.2+, `MetricsAspectsAutoConfiguration` é `@ConditionalOnBooleanProperty("management.observations.annotations.enabled")` — e o default é **desligado**. Sem essa propriedade, `@Timed` e `@Counted` compilam, a aplicação sobe, os métodos rodam e **nenhum meter é criado**. Não há warning.

```yaml
management:
  observations:
    annotations:
      enabled: true
```

A outra metade da armadilha é que `spring-boot-starter-aop` precisa estar no classpath. Sem ele a condição nem é avaliada.

### 2. O counter cujo nome desaparece

`_created` é um sufixo **reservado** pelo OpenMetrics (marca a série de timestamp de criação de um counter). A biblioteca cliente remove sufixos reservados do nome base antes de acrescentar `_total`. Resultado medido, com dois counters idênticos a menos de uma palavra:

```
demo.orders.created    ->  demo_orders_total              <- a palavra sumiu
demo.orders.accepted   ->  demo_orders_accepted_total
```

Falha do pior jeito possível: a aplicação sobe, a métrica é registrada, o valor está certo, e toda query escrita como `orders_created_total` casa com nada e desenha uma linha reta no zero — que se lê exatamente como "nenhum pedido". Por isso o counter de negócio aqui chama `orders.accepted`.

### 3. Self-invocation: o trabalho acontece, a medição não

`@Timed` é AOP, com a mesma regra de proxy do `@Transactional`: só vale para a chamada que entra pelo proxy.

```java
public int processPendingBatchViaSelfInvocation() {
    pending.forEach(order -> processAnnotated(order.getId()));  // this., não o proxy
}
```

O lote processa os pedidos corretamente e `orders.processing.annotated` não registra nada. É pior que o caso do `@Transactional`: transação faltando acaba corrompendo dado e alguém percebe; métrica faltando só deixa um gráfico em zero que todo mundo lê como "sem tráfego". `AnnotatedTimerIntegrationTest` trava as duas metades — o contador do lote sobe (essa chamada veio pelo proxy), o timer interno não.

### 4. O gauge que vira `NaN` e ninguém avisa

O Micrometer guarda referência **fraca** ao objeto que o gauge lê — decisão correta: instrumentar não pode manter vivo. A consequência morde:

```
POST /demo/gauge-reference
{"danglingGauge":"NaN","anchoredGauge":42.0,"collected":true}
```

Dois gauges, mesmo valor inicial. Um foi registrado sobre uma variável local, o outro sobre um campo de bean singleton. Depois do GC o primeiro reporta `NaN` para sempre — sem exceção, sem log. A linha no dashboard simplesmente para, e quem olha entende que o valor está estável.

O conserto é só manter a referência. O motivo de demonstrar em vez de afirmar é que a versão quebrada é a que se escreve naturalmente ao registrar um gauge dentro de um método.

### 5. Cardinalidade: 200 clientes, 10 séries

Cada combinação distinta de valores de tag é uma série temporal — na memória da aplicação e de novo no Prometheus. A identidade: **séries = métrica × produto dos valores distintos de cada tag**. Duas tags de cem valores são dez mil séries a partir de uma linha de instrumentação.

O erro passa em code review sem esforço: "contar pedidos por cliente" é uma frase que um PM diria.

```
POST /demo/cardinality?customers=200
{"distinctCustomers":200,"byCustomerSeries":10,"byChannelSeries":3,"totalSeries":143}
```

Duzentos clientes distintos, **dez** séries. A defesa:

```java
MeterFilter.maximumAllowableTags("orders.by_customer", "customer", 10, MeterFilter.deny());
MeterFilter.maximumAllowableMetrics(5_000);   // rede de segurança global
```

Negar é a opção bruta e é o default certo: a alternativa — dobrar o excedente num balde "outros" — mantém a contagem limitada e esconde que o limite foi atingido.

E a maior fonte de séries desta aplicação não é nenhuma métrica de negócio:

```
http_server_requests_seconds_bucket     666 séries
orders_amount_BRL_bucket                 21
orders_processing_seconds_bucket         14
orders_by_customer_total                 10
```

O histograma HTTP que eu mesmo liguei custa 666 séries. Daí o painel "Séries temporais no Prometheus" no dashboard: cardinalidade não explode de uma vez, cresce a cada deploy que acrescenta uma tag.

### 6. Percentil não é agregável — e histograma não é de graça

A média dos p95 de três instâncias **não é** o p95 do conjunto. É um número sem significado. Por isso se publica bucket e se calcula o quantil no Prometheus:

```promql
histogram_quantile(0.95, sum by (le) (rate(orders_processing_seconds_bucket[5m])))
```

Os dois timers deste projeto existem lado a lado justamente para mostrar a diferença no texto exposto:

```
# agregável — buckets, com fronteira exata no objetivo
orders_processing_seconds_bucket{outcome="paid",le="0.3"} ...

# não agregável — p95 calculado dentro desta JVM
orders_processing_annotated_seconds{quantile="0.95"} 0.569376768
```

O que normalmente se omite é o preço. `percentilesHistogram(true)` emite o conjunto default do Micrometer: medido nesta aplicação, **75 buckets** por combinação de tags, contra **7** dos objetivos explícitos.

```
com percentilesHistogram(true):  0.001 0.001048576 0.001398101 ... 28.633115306 30.0 +Inf   (75)
só com serviceLevelObjectives:   0.05 0.1 0.2 0.3 0.5 1.0 +Inf                              (7)
```

Sete fronteiras respondem "que fração cumpriu os 300 ms" com exatidão e estimam um p99 grosseiramente. O conjunto default faz o inverso. Escolher é o trabalho; ligar tudo é como um Prometheus fica sem memória.

### 7. O `MeterFilter` que capturou o meter errado

Esta apareceu durante o desenvolvimento, não na teoria. O filtro de SLO testava o nome assim:

```java
if (!id.getName().startsWith("orders.processing")) return config;
```

`"orders.processing.annotated".startsWith("orders.processing")` é **true**. O filtro substituiu os percentis client-side do timer anotado pelos próprios buckets — apagando exatamente o contraste que os dois meters existem para mostrar. Um `equals` resolve. Prefixo em `MeterFilter` é conveniente e captura o que você não previu.

### 8. O gauge que consulta o banco a cada scrape

A versão tentadora:

```java
Gauge.builder("orders.pending", repository, r -> r.countByStatus(PENDING))
```

Lê lindamente, e significa uma query **a cada scrape**, na thread de scrape. A 15 segundos de intervalo em uma dúzia de instâncias é carga contínua que ninguém pediu, e uma query lenta vira timeout de scrape — que aparece como a instância inteira sumindo do dashboard, não como query lenta. Aqui o gauge lê um `AtomicLong` que o serviço atualiza fora do caminho do scrape.

## Nome em Java x nome no Prometheus

A tradução não é óbvia e toda query é escrita contra o lado direito:

| Micrometer | Exposto |
|---|---|
| `orders.accepted` (Counter) | `orders_accepted_total` |
| `orders.pending` (Gauge, baseUnit `orders`) | `orders_pending_orders` |
| `orders.processing` (Timer) | `orders_processing_seconds_bucket` / `_count` / `_sum` / `_max` |
| `orders.amount` (Summary, baseUnit `BRL`) | `orders_amount_BRL_bucket` |
| `orders.payment.failures` (Counter) | `orders_payment_failures_total` |
| `demo.orders.created` (Counter) | `demo_orders_total` ← sufixo reservado |

Ponto vira sublinhado, counter ganha `_total`, a unidade base entra **no meio do nome**, e `_created` some.

## A stack completa

```bash
./gradlew bootJar && docker compose up -d --build
```

| | |
|---|---|
| Aplicação | http://localhost:8080 |
| Prometheus | http://localhost:9090 |
| Grafana | http://localhost:3000 (login anônimo, dashboard já provisionado) |

Tudo provisionado por arquivo — datasource, dashboard e regras de alerta sobem prontos, sem clique:

```
docker/prometheus/prometheus.yml          scrape de 5s + external labels
docker/prometheus/alert-rules.yml         5 regras
docker/grafana/provisioning/datasources/  aponta para o Prometheus do compose
docker/grafana/provisioning/dashboards/   carrega o diretório abaixo
docker/grafana/dashboards/orders.json     12 painéis
```

Gerar carga para os painéis terem o que mostrar (um dashboard contra um serviço parado não prova nada: os buckets ficam vazios e um erro de PromQL é indistinguível de um acerto):

```bash
curl -X POST "localhost:8080/load?orders=300"
# {"ordersCreated":300,"ordersProcessed":300,"elapsedMillis":3856}
```

300 pedidos criados e processados em 3,9 s, com virtual threads — as chamadas simuladas ao gateway são quase só espera, e `ExecutorService` é `AutoCloseable` desde o Java 19, então fechar o executor espera as tarefas e o tempo medido é o real.

## Os painéis, com as queries medidas

Cada painel tem no Grafana a descrição do **porquê** da métrica, não só do que mostra.

```promql
# Pedidos por minuto, por canal         -> MOBILE 136, PARTNER_API 118, WEB 89
sum by (channel) (rate(orders_accepted_total[1m])) * 60

# Cumprimento do SLO de 300 ms          -> 0.94
sum(rate(orders_processing_seconds_bucket{le="0.3"}[5m]))
  / sum(rate(orders_processing_seconds_count[5m]))

# p95 do serviço inteiro                -> 0.45 s
histogram_quantile(0.95, sum by (le) (rate(orders_processing_seconds_bucket[5m])))

# Falhas por motivo, por minuto         -> CARD_EXPIRED 5.4, INSUFFICIENT_FUNDS 1.3, ...
sum by (reason) (rate(orders_payment_failures_total[5m])) * 60

# Valor do pedido, p90                  -> R$ 2.268
histogram_quantile(0.90, sum by (le) (rate(orders_amount_BRL_bucket[5m])))

# Latência HTTP p95 por rota (uri com template, nunca o caminho resolvido)
histogram_quantile(0.95, sum by (le, uri) (rate(http_server_requests_seconds_bucket[5m])))

# Saúde do componente, com histórico    -> 1
component_health

# Cardinalidade do próprio Prometheus   -> 1658 séries
prometheus_tsdb_head_series
topk(10, count by (__name__) ({__name__=~"orders.*|http.*|jvm.*"}))
```

O painel de SLO desenha o objetivo como limiar tracejado em 95%: a série abaixo da linha é literalmente o que a regra `ProcessingSloBurn` dispara.

## Alertas, e um deles disparando de verdade

```
PaymentGatewayDown       inactive
InstanceDown             inactive
NoOrdersAccepted         inactive
PaymentFailureRateHigh   inactive
ProcessingSloBurn        pending     <- 94% contra um objetivo de 95%
```

As regras são escritas contra o **objetivo**, não contra um percentil:

```yaml
- alert: ProcessingSloBurn
  expr: |
    sum(rate(orders_processing_seconds_bucket{le="0.3"}[10m]))
      / sum(rate(orders_processing_seconds_count[10m])) < 0.95
  for: 10m
```

"95% dos pedidos processam em menos de 300 ms" é uma frase com que alguém concordou; "o p95 é 310 ms" é um número que precisa ser traduzido toda vez que é lido.

`InstanceDown` usa `up`, que o **Prometheus sintetiza** a cada scrape — por isso ainda dispara quando a aplicação não consegue publicar absolutamente nada. Alerta baseado só em métrica da própria aplicação não detecta a aplicação morta.

## Health indicator com histórico

`/actuator/health` responde a pergunta *agora*, para um load balancer, e não guarda histórico — "ficou fora quatro minutos na terça" é uma pergunta que ele não responde. O mesmo estado publicado como gauge responde:

```bash
curl -X POST "localhost:8080/demo/gateway?reachable=false"
curl -i localhost:8080/actuator/health        # 503, status DOWN, reason "connection refused"
# component_health{component="paymentGateway"} = 0
```

```promql
min_over_time(component_health{component="paymentGateway"}[5m]) == 0
```

Note o 503: um indicador customizado contribui para o agregado por padrão. Isso costuma estar certo e ocasionalmente é catastrófico — um indicador que checa dependência não essencial tira o pod do load balancer quando essa dependência pisca. Vale decidir por indicador, não por default.

## Como rodar os testes

```bash
./gradlew test
```

34 testes. Os unitários usam `SimpleMeterRegistry` — um registry em memória com a mesma semântica do real, o que torna "isso registrou o que eu acho que registrou" um teste unitário comum. Afirmar sobre métrica só pelo texto raspado deixa uma tag errada a três camadas de distância da linha que a causou.

| Classe | Testes | O que cobre |
|---|---|---|
| `OrderMetricsTest` | 8 | os quatro tipos, dedup por nome+tags, outcome decidido depois do trabalho |
| `MeterFilterTest` | 6 | tags comuns, denial, buckets de SLO, teto de cardinalidade |
| `GaugeReferenceDemoTest` | 1 | gauge pendurado vira `NaN`, ancorado sobrevive |
| `PrometheusExpositionIntegrationTest` | 10 | nomes expostos, buckets exatos, quantis client-side, sufixo reservado |
| `AnnotatedTimerIntegrationTest` | 4 | aspecto pelo proxy x self-invocation |
| `CardinalityIntegrationTest` | 2 | o teto instalado no registry real e no texto raspado |
| `HealthMetricsIntegrationTest` | 3 | indicador, agregado DOWN, gauge espelhando |

Testcontainers com **container singleton** (campo estático, inicializador estático, sem `@Testcontainers`): o Spring cacheia o contexto entre classes de teste, e a extensão pararia o container ao fim da primeira classe, deixando a segunda com um contexto vivo apontando para um banco morto.

## RED, USE e os três pilares

Dois métodos clássicos para decidir o que instrumentar:

- **RED** (serviços): *Rate*, *Errors*, *Duration* — `orders_accepted_total`, `orders_payment_failures_total`, `orders_processing_seconds`.
- **USE** (recursos): *Utilization*, *Saturation*, *Errors* — `hikaricp_connections_active` (U), `hikaricp_connections_pending` (S), `hikaricp_connections_timeout_total` (E).

O painel do pool existe por isso: "aguardando" acima de zero de forma sustentada é saturação, e é o sinal que chega antes da latência subir.

**Pull x push.** O Prometheus raspa em vez de receber, o que dá de graça o `up` por alvo e um ponto único de controle de taxa. Para job efêmero que termina antes do próximo scrape, a saída é o Pushgateway — e ele é a exceção, não o padrão: métrica empurrada não tem `up`, e um job que para de empurrar fica indistinguível de um job que nunca existiu.

**Três pilares.** Métrica responde "o quê e quando" com custo constante; log responde "o que exatamente aconteceu neste caso" com custo por evento; trace responde "onde no caminho". Métrica não substitui as outras duas — o `exception` tag do `@Timed` diz que houve falha, não qual pedido. O caminho natural daqui é Micrometer Tracing propagando o trace id para os três.

## Decisões de escopo

- **Gateway de pagamento simulado.** O que a instrumentação precisa de um gateway é distribuição de latência com cauda e falhas com causas distinguíveis. Um cliente HTTP real traria configuração e instabilidade sem acrescentar um insight sobre métricas. A latência é de cauda longa de propósito: distribuição uniforme deixa p50, p95 e p99 quase iguais, que é exatamente a forma que faz percentil parecer inútil.
- **Jar construído no host.** O `Dockerfile` só copia. Um multi-stage seria mais autocontido e baixaria o Gradle inteiro a cada build frio — minutos de espera que não provam nada sobre métricas.
- **Exposição restrita.** `management.endpoints.web.exposure.include` lista só o necessário. `*` é cômodo numa demonstração e passivo em qualquer outro lugar: `/actuator/env` e `/actuator/heapdump` não são coisas para deixar alcançáveis.

## O que levar de lição

Instrumentar é fácil; instrumentar de modo que o gráfico seja verdade é o trabalho. As três perguntas que separam uma coisa da outra:

1. **Este valor pode decrescer?** Se pode, não é counter. Se não pode, não é gauge.
2. **Quantos valores distintos esta tag pode ter, no limite?** Se a resposta depende do tráfego, a tag não entra.
3. **Este número sobrevive à agregação entre instâncias?** Percentil não sobrevive. Bucket sobrevive.
