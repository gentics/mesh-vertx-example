package com.gentics.mesh.example;

import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_TEMPLATE_DIRECTORY;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.IOUtils;

import com.gentics.mesh.core.rest.graphql.GraphQLRequest;
import com.gentics.mesh.core.rest.graphql.GraphQLResponse;
import com.gentics.mesh.core.rest.node.WebRootResponse;
import com.gentics.mesh.rest.client.MeshRestClient;
import com.gentics.mesh.util.URIUtils;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.impl.Utils;
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;
import rx.Single;

public class Server extends AbstractVerticle {

	static {
		// Enable if you want to edit the templates live
		//System.setProperty("io.vertx.ext.web.TemplateEngine.disableCache", "true");
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
				System.out.println(sub.getData().encodePrettily());
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
		System.out.println(path);
		loadByPath(path).subscribe(sub -> {
			JsonObject data = sub.getData();
			// We render different templates for each schema type. Category
			// nodes show products and thus the productList template is
			// utilized for those nodes.
			System.out.println(data.encodePrettily());
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

			// The webroot API may have returned binary data for the specified path. We
			// pass the binary data along and return it to the client.
			if (response.isDownload()) {
				String contentType = response.getDownloadResponse().getContentType();
				rc.response().putHeader(CONTENT_TYPE, contentType);
				rc.response().end(response.getDownloadResponse().getBuffer());
				// Note that we are not calling rc.next() which
				// will prevent the execution of the template route handler.
				return;
			} else {
				// Return a 404 response for all other cases
				rc.response().setStatusCode(404).end("Not found");
			}
		});
	}

	@Override
	public void start() throws Exception {
		// Login to mesh on http://localhost:8080
		log.info("Connecting to Gentics Mesh..");
		client = MeshRestClient.create("demo.getmesh.io", 443, true, vertx);

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

		vertx.createHttpServer().requestHandler(router::accept).listen(3000);
	}

	private void templateHandler(RoutingContext rc) {
		// String file = Utils.pathOffset(rc.normalisedPath(), rc);
		String file = rc.get("tmplName");
		engine.render(rc, new File(DEFAULT_TEMPLATE_DIRECTORY).getAbsolutePath(), Utils.normalizePath(file), res -> {
			if (res.succeeded()) {
				rc.response().putHeader(CONTENT_TYPE, DEFAULT_CONTENT_TYPE).end(res.result());
			} else {
				rc.fail(res.cause());
			}
		});
	}

	private Single<WebRootResponse> resolvePath(String path) {
		path = URIUtils.encodeFragment(path);
		// Load the node using the given path. The expandAll parameter is set to true
		// in order to also expand nested references in the located content. Doing so
		// will avoid the need of further requests.
		return client.webroot("demo", "/" + path).toSingle();
	}

	private Single<GraphQLResponse> loadTopNav() {
		return client.graphql("demo", new GraphQLRequest().setQuery(topNavQuery)).toSingle();
	}

	private Single<GraphQLResponse> loadByPath(String path) {
		String query = byPathQuery.replaceAll("PATH", path);
		return client.graphql("demo", new GraphQLRequest().setQuery(query)).toSingle();
	}
}
