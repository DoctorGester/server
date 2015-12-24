package com.dg;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author doc
 */
public class Utils {
	public static Map<String, List<String>> splitQuery(String url) throws UnsupportedEncodingException {
		Map<String, List<String>> queryPairs = new LinkedHashMap<>();

		if (url == null) {
			return queryPairs;
		}

		String[] pairs = url.split("&");

		for (String pair : pairs) {
			int idx = pair.indexOf("=");
			String key = idx > 0 ? URLDecoder.decode(pair.substring(0, idx), "UTF-8") : pair;

			if (!queryPairs.containsKey(key)) {
				queryPairs.put(key, new LinkedList<>());
			}

			String value = idx > 0 && pair.length() > idx + 1 ? URLDecoder.decode(pair.substring(idx + 1), "UTF-8") : null;
			queryPairs.get(key).add(value);
		}
		return queryPairs;
	}
}
