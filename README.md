# Environment setup
## Prerequisites

* Docker
* Docker Compose
* Maven
* Java 17
* jq
* curl

## Starting Kafka cluster

```shell
docker-compose -f provisioning/docker-compose.yml up -d
```

# Data Quality Rules Demo

The goal of this demo is to show an example of data quality rule and to see event condition actions like DLQ that can be applied when validation fails

## Initializing topics

```shell
docker-compose -f provisioning/docker-compose.yml exec broker kafka-topics --bootstrap-server localhost:9092  --create --topic membership
docker-compose -f provisioning/docker-compose.yml exec broker kafka-topics --bootstrap-server localhost:9092  --create --topic membership-dlq
```

## Registering the Schema

```shell
jq -n --rawfile schema data-quality-rules/membership.v1.avsc '{schema: $schema}' |  curl -X POST -d @- -H "Content-Type: application/json" --silent http://localhost:8081/subjects/membership-value/versions  | jq
```

Let's observe the registered schema

```shell
curl http://localhost:8081/subjects/membership-value/versions/latest
```

## Registering Metadata Rules

```shell
curl http://localhost:8081/subjects/membership-value/versions \
--header "Content-Type: application/json" --header "Accept: application/json" \
--data "@data-quality-rules/membership-metadata-rules.json" | jq
```

A new schema version has been registered with the additional metadata

```shell
http://localhost:8081/subjects/membership-value/versions/latest
```

## Registering Data Quality Rules

```shell
curl http://localhost:8081/subjects/membership-value/versions \
--header "Content-Type: application/json" --header "Accept: application/json" \
--data "@data-quality-rules/membership-dataquality-rules.json" | jq
```

A new schema version has been registered with the additional metadata

```shell
curl http://localhost:8081/subjects/membership-value/versions/latest
```

## Testing the rules with valid and invalid records

Open 3 terminals:
 * Membership topic producer
 * Membership topic consumer
 * Membership DLQ topic consumer


### Membership topic producer
```shell
kafka-avro-console-producer --topic membership  --bootstrap-server localhost:9092  --property schema.registry.url=http://localhost:8081 --property auto.register.schemas=false --property avro.use.logical.type.converters=true --property value.schema.id=`curl -s http://localhost:8081/subjects/membership-value/versions/latest | jq ".id"` --property bootstrap.servers=localhost:9092 --property bootstrap.servers=localhost:9092 --property dlq.auto.flush=true 
```
Let's produce a valid and an invalid record

```shell
{"start_date":20120,"end_date":20150,"email":"admin@example.com","ssn":"123-456-789"}
{"start_date":20120,"end_date":20150,"email":"admin<at>example.com","ssn":"123-456-789"}
```

### Membership topic consumer

Valid records are consumer and printed 
```shell
kafka-avro-console-consumer --topic membership  --bootstrap-server localhost:9092  --property schema.registry.url=http://localhost:8081 --from-beginning --property use.latest.version=true
```

### Membership DLQ topic consumer

Invalid records are routed to the DLQ topic, note that headers show message rejection reason

```shell
kafka-console-consumer --topic membership-dlq  --bootstrap-server localhost:9092  --property schema.registry.url=http://localhost:8081 --from-beginning --property print.headers=true --from-beginning
```

# Migration Demo

## Building Producer/Consumer applications
```shell
mvn clean package -f migration/data-contract-migration-v1/pom.xml
mvn clean package -f migration/data-contract-migration-v2/pom.xml
```

## Initializing artifacts/resources

```shell
docker-compose -f provisioning/docker-compose.yml exec broker kafka-topics --bootstrap-server localhost:9092  --create --topic membership-migration
```

## Registering the schema v1

```shell
jq -n --rawfile schema migration/data-contract-migration-v1/src/main/resources/schema/membership.v1.avsc '{schema: $schema, metadata: { properties: { app_version: 1 }}}' |  curl -X POST -d @- -H "Content-Type: application/json" --silent http://localhost:8081/subjects/membership-migration-value/versions  | jq
```

## Registering the schema v2 (breaking changes)

We try to register an incompatible change

```shell
jq -n --rawfile schema migration/data-contract-migration-v2/src/main/resources/schema/membership.v2.avsc '{schema: $schema, metadata: { properties: { app_version: 2 }}}' |  curl -X POST -d @- -H "Content-Type: application/json" --silent http://localhost:8081/subjects/membership-migration-value/versions  | jq
```

The SR returns an error message as we are trying to register a breaking error change.
```shell
{
  "error_code": 409,
  "message": "Schema being registered is incompatible with an earlier schema for subject \"membership-migration-value\"
 ...
```

To fix this we configure Data Contracts compatibility group based on custom property 'app_version'

```shell
curl -XPUT -d '{ "compatibilityGroup": "app_version" }' -H "Content-Type: application/json"  http://localhost:8081/config/membership-migration-value
```

Let's try to register again the breaking schema changes
```
jq -n --rawfile schema migration/data-contract-migration-v2/src/main/resources/schema/membership.v2.avsc '{schema: $schema, metadata: { properties: { app_version: 2 }}}' |  curl -X POST -d @- -H "Content-Type: application/json" --silent http://localhost:8081/subjects/membership-migration-value/versions  | jq
```

Let's register the migration rules
```shell
curl http://localhost:8081/subjects/membership-migration-value/versions \
--header "Content-Type: application/json" --header "Accept: application/json" \
--data "@migration/data-contract-migration-v2/src/main/resources/membership-migration-rules.json" | jq
```

## Observing migration rules in action

Let's open 4 distinct terminal shell to observe Producers/Consumers of both V1/V2 in action

We do expect that consumers are able to read messages produced with both schema versions. How?

* Migration rules are applied on both directions
* Record transformations are applied to downgrade Records V2->V1 when a Consumer V1 is reading a V2 record
* Record transformations are applied to upgrade Records V1->V2 when a Consumer V2 is reading a V1 record
  
### Consumer at compatibility group 1 ###


```shell
java -cp migration/data-contract-migration-v1/target/data-contract-migration-v1-1.0.0-SNAPSHOT-jar-with-dependencies.jar  com.paoven.datacontracts.migrations.ConsumerV1
```

### Consumer at compatibility group 2 ###

```shell
java -cp migration/data-contract-migration-v2/target/data-contract-migration-v2-1.0.0-SNAPSHOT-jar-with-dependencies.jar  com.paoven.datacontracts.migrations.ConsumerV2
```

### Producer at compatibility group 1
```shell
java -cp  migration/data-contract-migration-v1/target/data-contract-migration-v1-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.paoven.datacontracts.migrations.ProducerV1
```
Let's enter 'g' at command prompt at least one time to create a V1 Membership record
```shell
ProducerV1:78] - Type 'g' to generate a payload, 'e' to exit
g
[ProducerV1:52] - Sending Membership V1 record {"start_date": "2022-01-30", "end_date": "2022-01-31", "email": "account-559688@example.com", "ssn": "964-97-8180"}
[ProducerV1:58] - ================

```
### Producer at compatibility group 2
```shell
java -cp  migration/data-contract-migration-v2/target/data-contract-migration-v2-1.0.0-SNAPSHOT-jar-with-dependencies.jar com.paoven.datacontracts.migrations.ProducerV2
```

Let's enter 'g' at command prompt at least one time to create a V1 Membership record

```shell
[ProducerV2:78] - Type 'g' to generate a payload, 'e' to exit
g
[ProducerV2:52] - Sending Membership V2 record {"start_date": "2024-04-30", "end_date": "2024-05-01", "email": "account-253567@example.com", "socialSecurityNumber": "153-34-2797"}
[ProducerV2:58] - ================
```