package com.gentics.mesh.example;

import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_TEMPLATE_DIRECTORY;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import com.gentics.mesh.core.rest.error.HttpStatusCodeErrorException;
import com.gentics.mesh.core.rest.navigation.NavigationElement;
import com.gentics.mesh.core.rest.navigation.NavigationResponse;
import com.gentics.mesh.core.rest.node.WebRootResponse;
import com.gentics.mesh.etc.config.AuthenticationOptions.AuthenticationMethod;
import com.gentics.mesh.json.JsonUtil;
import com.gentics.mesh.query.impl.NavigationRequestParameter;
import com.gentics.mesh.query.impl.NodeRequestParameter;
import com.gentics.mesh.query.impl.NodeRequestParameter.LinkType;
import com.gentics.mesh.rest.MeshRestClient;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;

public class Server extends AbstractVerticle {

	private static final HandlebarsTemplateEngine engine = HandlebarsTemplateEngine.create();

	private static MeshRestClient client;

	// Convenience method so you can run it in your IDE
	public static void main(String[] args) {

		VertxOptions options = new VertxOptions();
		Vertx vertx = Vertx.vertx(options);

		client = MeshRestClient.create("localhost", 8080, vertx, AuthenticationMethod.BASIC_AUTH);
		client.setLogin("webclient", "webclient");
		client.login().toBlocking().first();

		vertx.deployVerticle(new Server());

	}

	@Override
	public void start() throws Exception {

		Router router = Router.router(vertx);
		router.routeWithRegex("/(.*)").handler(routingContext -> {
			String path = routingContext.pathParam("param0");
			System.out.println("Path: {" + path + "}");

			if (path.isEmpty()) {
				Future<NavigationResponse> navRootFuture = client.navroot("demo", "/", new NavigationRequestParameter().setMaxDepth(1),
						new NodeRequestParameter().setResolveLinks(LinkType.SHORT));
				navRootFuture.setHandler(response -> {
					NavigationResponse navRootResponse = response.result();
					Map<String, String> breadcrumb = new HashMap<>();
					for (NavigationElement element : navRootResponse.getRoot().getChildren()) {
						if (element.getNode().getSchema().getName().equals("category")) {
							breadcrumb.put(element.getNode().getFields().getStringField("name").getString(), element.getNode().getPath());
						}
					}
					routingContext.put("breadcrumb", breadcrumb);
					routingContext.put("templateName", "welcome.hbs");
					routingContext.next();
				});
			} else {

				// Resolve the node using the webroot api
				client.webroot("demo", "/" + path).setHandler(response -> {
					WebRootResponse webrootResponse = response.result();
					if (webrootResponse.isBinary()) {
						String contentType = webrootResponse.getDownloadResponse().getContentType();
						routingContext.response().putHeader(CONTENT_TYPE, contentType).end(webrootResponse.getDownloadResponse().getBuffer());
					} else {

						String schemaName = webrootResponse.getNodeResponse().getSchema().getName();
						if ("category".equals(schemaName)) {
							routingContext.put("templateName", "productList.hbs");
							routingContext.put("resolvedNode", null);
							routingContext.response().putHeader(CONTENT_TYPE, "text/html");
							routingContext.next();
							return;
						}
						if ("vehicle".equals(schemaName)) {
							routingContext.put("templateName", "productDetail.hbs");
							routingContext.put("product", webrootResponse.getNodeResponse());
							routingContext.response().putHeader(CONTENT_TYPE, "text/html");
							routingContext.next();
							return;
						}
						routingContext.response().setStatusCode(404).end("Not found");
					}
				});
			}
		});

		router.route().handler(routingContext -> {

			String file = DEFAULT_TEMPLATE_DIRECTORY + File.separator + routingContext.get("templateName");
			engine.render(routingContext, file, res -> {
				if (res.succeeded()) {
					routingContext.response().putHeader(CONTENT_TYPE, DEFAULT_CONTENT_TYPE).end(res.result());
				} else {
					routingContext.fail(res.cause());
				}
			});
		});
		vertx.createHttpServer().requestHandler(router::accept).listen(3000);
	}
}