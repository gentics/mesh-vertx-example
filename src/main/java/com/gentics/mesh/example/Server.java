package com.gentics.mesh.example;

import static io.vertx.core.http.HttpHeaders.CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_CONTENT_TYPE;
import static io.vertx.ext.web.handler.TemplateHandler.DEFAULT_TEMPLATE_DIRECTORY;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import com.gentics.mesh.core.rest.navigation.NavigationElement;
import com.gentics.mesh.core.rest.navigation.NavigationResponse;
import com.gentics.mesh.core.rest.node.NodeResponse;
import com.gentics.mesh.core.rest.node.WebRootResponse;
import com.gentics.mesh.etc.config.AuthenticationOptions.AuthenticationMethod;
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
import io.vertx.ext.web.Router;
import io.vertx.ext.web.templ.HandlebarsTemplateEngine;
import rx.Single;

public class Server extends AbstractVerticle {

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

	@Override
	public void start() throws Exception {

		HandlebarsTemplateEngine engine = HandlebarsTemplateEngine.create();
		Router router = Router.router(vertx);
		router.routeWithRegex("/(.*)").handler(routingContext -> {
			String path = routingContext.pathParam("param0");

			if (path.equals("favicon.ico")) {
				routingContext.response().setStatusCode(404).end("Not found");
				return;
			}

			Single<Map<String, String>> breadcrumbObs = loadBreadcrumb();

			// Render the welcome page for root page requests
			if (path.isEmpty()) {
				breadcrumbObs.subscribe(breadcrumb -> {
					routingContext.put("breadcrumb", breadcrumb);
					routingContext.put("templateName", "welcome.hbs");
					routingContext.next();
				});
				return;
			}

			// Resolve the node using the webroot API.
			resolvePath(path).subscribe(response -> {

				// The response contains binary data which means that the
				// webroot API returned binary data for the specified path. We
				// pass the binary data along and return it to the client.
				if (response.isBinary()) {
					String contentType = response.getDownloadResponse().getContentType();
					routingContext.response().putHeader(CONTENT_TYPE, contentType);
					routingContext.response().end(response.getDownloadResponse().getBuffer());
					// Note that we are not calling routingContext.next() which
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
					Single.zip(childrenObs, breadcrumbObs, (children, breadcrumb) -> {
						routingContext.put("templateName", "productList.hbs");
						routingContext.put("category", jsonObject);
						routingContext.put("products", children);
						routingContext.put("breadcrumb", breadcrumb);
						routingContext.response().putHeader(CONTENT_TYPE, "text/html");
						routingContext.next();
						return null;
					}).subscribe();
					return;
				}

				// Show the productDetail page for nodes of type vehicle
				if ("vehicle".equals(schemaName)) {
					breadcrumbObs.subscribe(nav -> {
						routingContext.put("templateName", "productDetail.hbs");
						routingContext.put("product", jsonObject);
						routingContext.put("breadcrumb", nav);
						routingContext.response().putHeader(CONTENT_TYPE, "text/html");
						routingContext.next();
					});
					return;
				}

				// Return a 404 response for all other cases
				routingContext.response().setStatusCode(404).end("Not found");

			});

		});

		// Finally use the previously set context data to render the templates
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

	/**
	 * Load the child nodes of the given node.
	 * 
	 * @param response
	 * @return
	 */
	private Single<JsonObject> loadCategoryChildren(NodeResponse response) {
		return toSingle(
				client.findNodeChildren("demo", response.getUuid(), new NodeRequestParameter().setExpandAll(true).setResolveLinks(LinkType.SHORT)))
						.map(children -> toJsonNode(children));
	}

	/**
	 * Filter the navigation response to only include category nodes return a map which can be used within the handlebars template.
	 * 
	 * @param response
	 * @return
	 */
	private Map<String, String> filterNav(NavigationResponse response) {
		Map<String, String> breadcrumb = new HashMap<>();
		for (NavigationElement element : response.getRoot().getChildren()) {
			if (element.getNode().getSchema().getName().equals("category")) {
				breadcrumb.put(element.getNode().getFields().getStringField("name").getString(), element.getNode().getPath());
			}
		}
		return breadcrumb;
	}

	/**
	 * Transform the given object into a JsonObject which can be used within the handlebars template.
	 * 
	 * @param object
	 * @return
	 */
	private JsonObject toJsonNode(Object object) {
		String json = JsonUtil.toJson(object);
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
		return toSingle(client.webroot("demo", "/" + path, new NodeRequestParameter().setExpandAll(true).setResolveLinks(LinkType.SHORT)));
	}

	/**
	 * Load the breadcrumb data.
	 * 
	 * @return
	 */
	private Single<Map<String, String>> loadBreadcrumb() {
		return toSingle(client.navroot("demo", "/", new NavigationRequestParameter().setMaxDepth(1),
				new NodeRequestParameter().setResolveLinks(LinkType.SHORT))).map(response -> filterNav(response));
	}
}