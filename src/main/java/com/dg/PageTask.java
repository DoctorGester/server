package com.dg;

import de.neuland.jade4j.template.JadeTemplate;

import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/**
 * @author doc
 */
public interface PageTask {
	public void print(String text);
	public void printTemplate(JadeTemplate template);
	public void setHeader(String header, String value);
	public void setDate(String header, long value);
	public void setCode(int code);
	public String getCookie(String name);
	public void setCookie(String name, String value);
	public VirtualHost getHost();
	public Map<String, String> getQuery();
	public String getQueryString();
	public String getParam(String name);
	public OutputStream getOutputStream() throws IOException;
	public long getDate(String key);
	public String getHeader(String key);
	public HttpServletRequest getRequest();
}
