/*
 * Copyright 2019 Red Hat, Inc.
 *
 * Red Hat licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.vertx.ext.web.handler.graphql;

import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.RuntimeWiring;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.TypeDefinitionRegistry;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.CorsHandler;
import org.reactivestreams.Publisher;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;

import static graphql.schema.idl.RuntimeWiring.newRuntimeWiring;
import static io.vertx.core.http.HttpMethod.GET;
import static io.vertx.core.http.HttpMethod.POST;
import static java.util.stream.Collectors.toList;

/**
 * Backend for the Apollo Link compatibility tests.
 *
 * @author Thomas Segismont
 */
public class ApolloTestsServer extends AbstractVerticle {

  public static void main(String[] args) {
    Vertx.vertx().deployVerticle(new ApolloTestsServer());
  }

  private final TestData testData = new TestData();

  @Override
  public void start(Promise<Void> startPromise) throws Exception {
    Router router = Router.router(vertx);

    router.route().handler(CorsHandler.create("*").allowedMethods(EnumSet.of(GET, POST)));

    router.route("/graphql").handler(ApolloWSHandler.create(setupWsGraphQL()));

    GraphQLHandlerOptions graphQLHandlerOptions = new GraphQLHandlerOptions()
      .setRequestBatchingEnabled(true);
    router.route("/graphql").handler(GraphQLHandler.create(setupGraphQL(), graphQLHandlerOptions));

    HttpServerOptions httpServerOptions = new HttpServerOptions().setWebsocketSubProtocols("graphql-ws");
    vertx.createHttpServer(httpServerOptions)
      .requestHandler(router)
      .listen(8080, ar -> {
        if (ar.succeeded()) {
          System.out.println("Apollo tests server started");
          startPromise.complete();
        } else {
          startPromise.fail(ar.cause());
        }
      });
  }

  private GraphQL setupGraphQL() {
    String schema = vertx.fileSystem().readFileBlocking("links.graphqls").toString();

    SchemaParser schemaParser = new SchemaParser();
    TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema);

    RuntimeWiring runtimeWiring = newRuntimeWiring()
      .type("Query", builder -> builder.dataFetcher("allLinks", this::getAllLinks))
      .build();

    SchemaGenerator schemaGenerator = new SchemaGenerator();
    GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

    return GraphQL.newGraphQL(graphQLSchema)
      .build();
  }

  private GraphQL setupWsGraphQL() {
    String schema = vertx.fileSystem().readFileBlocking("counter.graphqls").toString();

    SchemaParser schemaParser = new SchemaParser();
    TypeDefinitionRegistry typeDefinitionRegistry = schemaParser.parse(schema);

    RuntimeWiring runtimeWiring = newRuntimeWiring()
      .type("Query", builder -> builder.dataFetcher("staticCounter", this::staticCounter))
      .type("Subscription", builder -> builder.dataFetcher("counter", this::counter))
      .build();

    SchemaGenerator schemaGenerator = new SchemaGenerator();
    GraphQLSchema graphQLSchema = schemaGenerator.makeExecutableSchema(typeDefinitionRegistry, runtimeWiring);

    return GraphQL.newGraphQL(graphQLSchema)
      .build();
  }

  private Object getAllLinks(DataFetchingEnvironment env) {
    boolean secureOnly = env.getArgument("secureOnly");
    return testData.links.stream()
      .filter(link -> !secureOnly || link.getUrl().startsWith("https://"))
      .collect(toList());
  }

  private Object staticCounter(DataFetchingEnvironment env) {
    int count = env.getArgument("num");
    Map<String, Object> counter = new HashMap<>();
    counter.put("count", count);
    return counter;
  }

  private Publisher<Object> counter(DataFetchingEnvironment env) {
    return subscriber -> {
      Map<String, Object> counter = new HashMap<>();
      counter.put("count", 1);

      subscriber.onNext(counter);
      subscriber.onComplete();
    };
  }

}
