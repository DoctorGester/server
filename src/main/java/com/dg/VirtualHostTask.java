package com.dg;

import de.neuland.jade4j.template.JadeTemplate;
import org.apache.commons.lang3.StringEscapeUtils;
import org.apache.commons.lang3.StringUtils;
import org.python.core.PyBaseCode;
import org.python.core.PyCode;
import org.python.core.PyDictionary;
import org.python.core.PyFrame;
import spark.Request;
import spark.Response;
import spark.Spark;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author doc
 */
public class VirtualHostTask implements PageTask {
	private VirtualHost host;
	private Request request;
	private Response response;
	private String queryString;
	private Map<String, Object> model = new HashMap<>();
	private Map<String, String> queryParams = new HashMap<>();
	private Map<String, String> bodyParams = new HashMap<>();
	private StringBuffer body;

	public VirtualHostTask(VirtualHost virtualHost, Request request, Response response) {
		this.host = virtualHost;
		this.request = request;
		this.response = response;

		body = new StringBuffer();
	}

	public String dispatch() {
		setHeader("Server", "Server powered by Spark");
		setDate("Date", System.currentTimeMillis());

		String uri = request.uri().substring(1);
		String query;

		String rewritten = host.rewriteUrl(uri);

		if (!rewritten.equals(uri)) {
			uri = rewritten;
			query = uri.substring(uri.indexOf("?") + 1);
		} else {
			query = request.raw().getQueryString();
		}

		if (uri.contains("?")) {
			uri = uri.substring(0, uri.indexOf("?"));
		}

		queryString = query == null ? "" : query;

		parseQuery(query);

		if (!displayPage(uri)) {
			Spark.halt(404);
		}

		return body.toString();
	}

	public void print(String text) {
		body.append(text);
	}

	public void printTemplate(JadeTemplate template){
		print(host.getTemplates().renderTemplate(template, model));
	}

	public void setHeader(String header, String value) {
		response.header(header, value);
	}

	public void setDate(String header, long value) {
		response.raw().setDateHeader(header, value);
	}

	public void setCode(int code) {
		response.status(code);
	}

	@Override
	public String getCookie(String name) {
		return request.cookie(name);
	}

	@Override
	public void setCookie(String name, String value) {
		response.cookie(name, value);
	}

	@Override
	public VirtualHost getHost() {
		return host;
	}

	@Override
	public String getQueryString() {
		return queryString;
	}

	private void parseQuery(String uri) {
		try {
			Map<String, List<String>> splitQuery = Utils.splitQuery(uri);
			queryParams = new HashMap<>();

			for (Map.Entry<String, List<String>> entry : splitQuery.entrySet()) {
				queryParams.put(entry.getKey(), entry.getValue().get(0));
			}
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}

	private void parseBody(String body) {
		try {
			if (!StringUtils.isAlphanumericSpace(body)) {
				bodyParams = new HashMap<>();
				return;
			}

			Map<String, List<String>> splitQuery = Utils.splitQuery(body);
			bodyParams = new HashMap<>();

			for (Map.Entry<String, List<String>> entry : splitQuery.entrySet()) {
				bodyParams.put(entry.getKey(), entry.getValue().get(0));
			}
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
	}

	public Map<String, String> getQuery() {
		return queryParams;
	}

	@Override
	public String getParam(String name) {
		// Lazy loading is important, or else multi-part uploads fail
		if (bodyParams == null) {
			parseBody(request.body());
		}

		return bodyParams.get(name);
	}

	@Override
	public OutputStream getOutputStream() throws IOException {
		return response.raw().getOutputStream();
	}

	@Override
	public long getDate(String key) {
		return request.raw().getDateHeader(key);
	}

	@Override
	public String getHeader(String key) {
		return request.headers(key);
	}

	@Override
	public HttpServletRequest getRequest() {
		return request.raw();
	}

	private boolean displayPage(String name){
		PyCode code = null;

		try {
			code = host.getPage(name);
		} catch (IOException e) {
			Spark.halt(501);
		}

		if (code == null)
			return false;

		PyDictionary globals = new PyDictionary();
		globals.put("page", this);
		globals.put("templates", host.getTemplates());
		globals.put("database", host.getDatabase());
		globals.put("model", model);

		response.type("text/html");

		try {
			code.call(new PyFrame((PyBaseCode) code, globals));
		} catch (Exception e){
			print(StringEscapeUtils.escapeHtml4(e.toString()));
			e.printStackTrace();
		}

		return true;
	}
}
