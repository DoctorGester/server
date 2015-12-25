package com.dg;

import spark.Request;
import spark.Route;

import javax.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static spark.Spark.*;

/**
 * @author doc
 */
public class Server {
	private static final String HEADER_HOST = "Host";

	private Map<String, VirtualHost> hosts;
	private Map<String, String> synonyms = new HashMap<>();

	private String defaultHost;

	public Server(int port) {
		port(port);
		threadPool(16);

		Route route = (request, response) -> {
			VirtualHost host = getHost(request);

			if (host == null) {
				halt(404);
				return "";
			}

			return host.dispatch(request, response);
		};

		before((request, response) -> {
			if (request.raw().getAttribute("org.eclipse.jetty.multipartConfig") == null) {
				MultipartConfigElement config = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));
				request.raw().setAttribute("org.eclipse.jetty.multipartConfig", config);
			}
		});

		get("/*", route);
		post("/*", route);

		after((request, response) -> {
			String accept = request.headers("Accept-Encoding");

			if (accept != null && accept.contains("gzip")) {
				response.header("Content-Encoding", "gzip");
			}
		});

		createHosts(Paths.get("sites"));
		initSynonyms(Paths.get("synonyms"));
	}

	private void initSynonyms(Path file) {
		try {
			if (!Files.exists(file)) {
				return;
			}

			Files.lines(file).forEach(line -> {
				String pair[] = line.split("=");
				synonyms.put(pair[0], pair[1]);

				if (pair[0].equals("default"))
					defaultHost = pair[1];
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void createHosts(Path root){
		hosts = new HashMap<>();

		try {
			Files.newDirectoryStream(root, path -> Files.isDirectory(path)).forEach(directory -> {
				hosts.put(directory.getFileName().toString().toLowerCase(), new VirtualHost(directory));
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private VirtualHost getHost(Request request){
		String host = request.headers(HEADER_HOST).toLowerCase().replaceAll(":\\d{1,5}", "");
		String name = synonyms.keySet().stream().filter(host::matches).findAny().orElse("");
		VirtualHost virtualHost = hosts.get(synonyms.get(name));

		if (virtualHost == null) {
			String newHost = defaultHost != null ? defaultHost : hosts.keySet().stream().findAny().orElse(null);
			virtualHost = hosts.get(newHost);
		}

		return virtualHost;
	}

	public static void main(String[] args) {
		int port = 80;

		for (String arg : args) {
			if (arg.matches("\\d{1,4}"))
				port = Integer.valueOf(arg);
		}

		new Server(port);
	}
}
