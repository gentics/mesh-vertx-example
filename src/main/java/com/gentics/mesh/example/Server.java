package com.gentics.mesh.example;

import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_TEMPLATE_DIRECTORY;

import java.io.IOException;
import java.util.Optional;

import org.apache.commons.io.IOUtils;

import com.gentics.mesh.core.rest.graphql.GraphQLRequest;
import com.gentics.mesh.core.rest.graphql.GraphQLResponse;
import com.gentics.mesh.rest.client.MeshBinaryResponse;
import com.gentics.mesh.rest.client.MeshResponse;
import com.gentics.mesh.rest.client.MeshRestClient;
import com.gentics.mesh.rest.client.MeshWebrootResponse;
import com.google.common.net.HttpHeaders;

import io.reactivex.Single;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.Utils;
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;

public class Server extends AbstractVerticle {

	static {
		// Enable if you want to edit the templates live
		// System.setProperty("io.vertx.ext.web.TemplateEngine.disableCache", "true");
	}

	private static final Logger log = LoggerFactory.getLogger(Server.class);

	private MeshRestClient client;

	private String topNavQuery;

	private String byPathQuery;

	private HandlebarsTemplateEngine engine;

	// Convenience method so you can run it in your IDE
	public static void main(String[] args) {
		VertxOptions options = new VertxOptions();
		Vertx vertx = Vertx.vertx(options);
		vertx.deployVerticle(new Server());
	}

	public void routeHandler(RoutingContext rc) {
		String path = rc.pathParam("param0");

		if (path.equals("favicon.ico")) {
			rc.response().setStatusCode(404).end("Not found");
			return;
		}

		// Render the welcome page for root page requests
		if (path.isEmpty()) {
			loadTopNav().subscribe(sub -> {
				rc.put("data", sub.getData());
				rc.put("tmplName", "welcome");
				rc.next();
			});
			return;
		}

		if (log.isDebugEnabled()) {
			log.debug("Handling request for path {" + path + "}");
		}

		if (path.endsWith(".jpg")) {
			handleImages(path, rc);
		} else {
			handlePage(path, rc);
		}
	}

	private void handlePage(String path, RoutingContext rc) {
		loadByPath(path).subscribe(sub -> {
			JsonObject data = sub.getData();
			// We render different templates for each schema type. Category
			// nodes show products and thus the productList template is
			// utilized for those nodes.
			String schemaName = data.getJsonObject("node").getJsonObject("schema").getString("name");
			switch (schemaName) {
			case "category":
				rc.put("tmplName", "productList.hbs");
				rc.put("data", data);
				rc.response().putHeader(CONTENT_TYPE, "text/html");
				rc.next();
				break;
			// Show the productDetail page for nodes of type vehicle
			case "vehicle":
				rc.put("tmplName", "productDetail.hbs");
				rc.put("data", data);
				rc.response().putHeader(CONTENT_TYPE, "text/html");
				rc.next();
				break;
			}
		}, error -> {
			rc.fail(error);
		});

	}

	private String getQuery(String name) throws IOException {
		return IOUtils.toString(getClass().getResourceAsStream("/queries/" + name + ".graphql"));
	}

	private void handleImages(String path, RoutingContext rc) {
		// Resolve image path using the webroot API.
		resolvePath(path).subscribe(response -> {

			MeshWebrootResponse meshResponse = response.getBody();
			// The webroot API may have returned binary data for the specified path. We
			// pass the binary data along and return it to the client.
			if (meshResponse.isBinary()) {
				MeshBinaryResponse binaryResponse = meshResponse.getBinaryResponse();
				String contentType = binaryResponse.getContentType();
				HttpServerResponse serverResponse = rc.response();

				Optional<String> length = response.getHeader(HttpHeaders.CONTENT_LENGTH);

				if (length.isPresent()) {
					serverResponse.putHeader(HttpHeaders.CONTENT_LENGTH, length.get());
				} else {
					serverResponse.setChunked(true);
				}

				rc.response().putHeader(CONTENT_TYPE, contentType);
				binaryResponse.getFlowable()
					.map(Buffer::buffer)
					.subscribe(serverResponse::write, rc::fail, serverResponse::end);
				// Note that we are not calling rc.next() which
				// will prevent the execution of the template route handler.
				return;
			} else {
				// Return a 404 response for all other cases
				rc.response().setStatusCode(404).end("Not found");
			}
		}, rc::fail);
	}

	@Override
	public void start() throws Exception {
		// Connect to Gentics Mesh on https://demo.getmesh.io or http://localhost:8080
		log.info("Connecting to Gentics Mesh..");
		client = MeshRestClient.create("demo.getmesh.io", 443, true);
		// client = MeshRestClient.create("localhost", 80, false, vertx);

		topNavQuery = getQuery("loadOnlyTopNav");
		byPathQuery = getQuery("loadByPath");
		engine = HandlebarsTemplateEngine.create();

		Router router = Router.router(vertx);
		router.routeWithRegex("/(.*)").handler(this::routeHandler).failureHandler(rc -> {
			log.error("Error handling request {" + rc.request().absoluteURI() + "}", rc.failure());
			rc.response().setStatusCode(500).end();
		});

		// Finally use the previously set context data to render the templates
		router.route().handler(this::templateHandler).failureHandler(rc -> {
			log.error("Error while rendering template {" + rc.get("tmplName") + "}", rc.failure());
			rc.response().setStatusCode(500).end();
		});

		log.info("Server running on port 3000");
		vertx.createHttpServer().requestHandler(router::accept).listen(3000);
	}

	private void templateHandler(RoutingContext rc) {
		String file = rc.get("tmplName");
		engine.render(rc, DEFAULT_TEMPLATE_DIRECTORY, Utils.normalizePath(file), res -> {
			if (res.succeeded()) {
				rc.response().putHeader(CONTENT_TYPE, DEFAULT_CONTENT_TYPE).end(res.result());
			} else {
				rc.fail(res.cause());
			}
		});
	}

	private Single<MeshResponse<MeshWebrootResponse>> resolvePath(String path) {
		// Load the node using the given path.
		return client.webroot("demo", "/" + path).getResponse();
	}

	private Single<GraphQLResponse> loadTopNav() {
		return client.graphql("demo", new GraphQLRequest().setQuery(topNavQuery)).toSingle();
	}

	private Single<GraphQLResponse> loadByPath(String path) {
		String query = byPathQuery;
		return client.graphql("demo", new GraphQLRequest().setQuery(query).setVariables(new JsonObject().put("path", path))).toSingle();
	}
}
