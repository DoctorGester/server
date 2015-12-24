package com.dg;

import de.neuland.jade4j.template.TemplateLoader;

import java.io.*;
import java.nio.file.Path;

/**
 * @author doc
 */
public class FileTemplateLoader implements TemplateLoader {
	private static final String encoding = "UTF-8";
	private static final String suffix = ".jade";
	private Path root;

	public FileTemplateLoader(Path root) {
		this.root = root;
	}

	public long getLastModified(String name) {
		File templateSource = getFile(name);
		return templateSource.lastModified();
	}

	public Reader getReader(String name) throws IOException {
		File templateSource = getFile(name);
		return new InputStreamReader(new FileInputStream(templateSource), encoding);
	}

	private File getFile(String name) {
		if (!name.endsWith(suffix))
			name += suffix;
		return root.resolve(name).toFile();
	}
}
