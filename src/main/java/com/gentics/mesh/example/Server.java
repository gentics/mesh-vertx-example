package com.gentics.mesh.example;

import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_TEMPLATE_DIRECTORY;
import static com.gentics.mesh.etc.config.AuthenticationOptions.AuthenticationMethod.BASIC_AUTH;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.gentics.mesh.core.rest.common.RestModel;
import com.gentics.mesh.core.rest.navigation.NavigationElement;
import com.gentics.mesh.core.rest.navigation.NavigationResponse;
import com.gentics.mesh.core.rest.node.NodeResponse;
import com.gentics.mesh.core.rest.node.WebRootResponse;
import com.gentics.mesh.json.JsonUtil;
import com.gentics.mesh.query.impl.NavigationRequestParameter;
import com.gentics.mesh.query.impl.NodeRequestParameter;
import com.gentics.mesh.query.impl.NodeRequestParameter.LinkType;
import com.gentics.mesh.rest.MeshRestClient;
import com.gentics.mesh.util.URIUtils;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;
import rx.Single;

public class Server extends AbstractVerticle {

	private static final Logger log = LoggerFactory.getLogger(Server.class);

	private MeshRestClient client;

	private HandlebarsTemplateEngine engine;

	// Convenience method so you can run it in your IDE
	public static void main(String[] args) {
		VertxOptions options = new VertxOptions();
		Vertx vertx = Vertx.vertx(options);
		vertx.deployVerticle(new Server());
	}

	/**
	 * Helper method that is used to transform the future into a single.
	 * 
	 * @param fut
	 * @return
	 */
	private <T> Single<T> toSingle(Future<T> fut) {
		return Single.create(sub -> {
			fut.setHandler(rh -> {
				if (rh.succeeded()) {
					sub.onSuccess(rh.result());
				} else {
					sub.onError(rh.cause());
				}
			});
		});
	}

	public void routeHandler(RoutingContext rc) {
		String path = rc.pathParam("param0");

		if (path.equals("favicon.ico")) {
			rc.response().setStatusCode(404).end("Not found");
			return;
		}

		Single<Map<String, String>> breadcrumbObs = loadBreadcrumb();

		// Render the welcome page for root page requests
		if (path.isEmpty()) {
			breadcrumbObs.subscribe(breadcrumb -> {
				rc.put("breadcrumb", breadcrumb);
				rc.put("tmplName", "welcome.hbs");
				rc.next();
			});
			return;
		}

		if (log.isDebugEnabled()) {
			log.debug("Handling request for path {" + path + "}");
		}
		// Resolve the node using the webroot API.
		resolvePath(path).subscribe(response -> {

			// The response contains binary data which means that the
			// webroot API returned binary data for the specified path. We
			// pass the binary data along and return it to the client.
			if (response.isBinary()) {
				String contentType = response.getDownloadResponse().getContentType();
				rc.response().putHeader(CONTENT_TYPE, contentType);
				rc.response().end(response.getDownloadResponse().getBuffer());
				// Note that we are not calling rc.next() which
				// will prevent the execution of the template route handler.
				return;
			}

			// Alternatively the response contains node JSON data which can
			// be used to render a template.
			JsonObject jsonObject = toJsonNode(response.getNodeResponse());

			// We render different templates for each schema type. Category
			// nodes show products and thus the productList template is
			// utilized for those nodes.
			String schemaName = response.getNodeResponse().getSchema().getName();

			if ("category".equals(schemaName)) {
				Single<JsonObject> childrenObs = loadCategoryChildren(response.getNodeResponse());
				Single.zip(childrenObs, breadcrumbObs,
						(children, breadcrumb) -> {
							rc.put("tmplName", "productList.hbs");
							rc.put("category", jsonObject);
							rc.put("products", children);
							rc.put("breadcrumb", breadcrumb);
							rc.response().putHeader(CONTENT_TYPE, "text/html");
							rc.next();
							return null;
						}).subscribe((e) -> {
						}, error -> {
							rc.fail(error);
						});
				return;
			}

			// Show the productDetail page for nodes of type vehicle
			if ("vehicle".equals(schemaName)) {
				breadcrumbObs.subscribe(nav -> {
					rc.put("tmplName", "productDetail.hbs");
					rc.put("product", jsonObject);
					rc.put("breadcrumb", nav);
					rc.response().putHeader(CONTENT_TYPE, "text/html");
					rc.next();
				});
				return;
			}

			// Return a 404 response for all other cases
			rc.response().setStatusCode(404).end("Not found");

		});
	}

	@Override
	public void start() throws Exception {
		// Login to mesh on http://localhost:8080
		log.info("Login into mesh..");
		client = MeshRestClient.create("localhost", 8080, vertx, BASIC_AUTH);
		client.setLogin("webclient", "webclient");
		client.login().doOnCompleted(() -> log.info("Login successful")).subscribe();
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
		String file = DEFAULT_TEMPLATE_DIRECTORY + File.separator + rc.get("tmplName");
		engine.render(rc, file, res -> {
			if (res.succeeded()) {
				rc.response().putHeader(CONTENT_TYPE, DEFAULT_CONTENT_TYPE)
				  .end(res.result());
			} else {
				rc.fail(res.cause());
			}
		});
	}

	/**
	 * Load the child nodes of the given node.
	 * 
	 * @param response
	 * @return
	 */
	private Single<JsonObject> loadCategoryChildren(NodeResponse response) {
		return toSingle(client.findNodeChildren("demo", response.getUuid(),
				new NodeRequestParameter().setExpandAll(true)
						.setResolveLinks(LinkType.SHORT)))
								.map(children -> toJsonNode(children));
	}

	/**
	 * Filter the navigation response to only include category nodes return a
	 * map which can be used within the handlebars template.
	 * 
	 * @param response
	 * @return
	 */
	private Map<String, String> filterNav(NavigationResponse response) {
		Map<String, String> breadcrumb = new HashMap<>();
		for (NavigationElement element : response.getRoot()
				.getChildren()) {
			if (element.getNode()
					.getSchema()
					.getName()
					.equals("category")) {
				breadcrumb.put(element.getNode()
						.getFields()
						.getStringField("name")
						.getString(),
						element.getNode().getPath());
			}
		}
		return breadcrumb;
	}

	/**
	 * Transform the given model into a JsonObject which can be used within the
	 * handlebars template.
	 * 
	 * @param model
	 * @return
	 */
	private JsonObject toJsonNode(RestModel model) {
		String json = JsonUtil.toJson(model);
		return new JsonObject(json);
	}

	/**
	 * Resolve the given path to a mesh node.
	 * 
	 * @param path
	 * @return
	 */
	private Single<WebRootResponse> resolvePath(String path) {
		path = URIUtils.encodeFragment(path);
		// Load the node using the given path. The expandAll parameter is set to true
		// in order to also expand nested references in the located content. Doing so 
		// will avoid the need of further requests.  
		return toSingle(client.webroot("demo", "/" + path,
				new NodeRequestParameter().setExpandAll(true)
						.setResolveLinks(LinkType.SHORT)));
	}

	/**
	 * Load the breadcrumb data.
	 * 
	 * @return
	 */
	private Single<Map<String, String>> loadBreadcrumb() {
		return toSingle(client.navroot("demo", "/",
				new NavigationRequestParameter().setMaxDepth(1),
				new NodeRequestParameter().setResolveLinks(LinkType.SHORT)))
						.map(response -> filterNav(response));
	}
}