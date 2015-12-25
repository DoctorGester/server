package com.dg;

import de.neuland.jade4j.JadeConfiguration;
import org.python.core.CompilerFlags;
import org.python.core.Py;
import org.python.core.PyCode;
import org.python.core.PyString;
import org.python.util.PythonInterpreter;
import spark.Request;
import spark.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.sql.SQLException;
import java.util.*;

/**
 * @author doc
 */
public class VirtualHost {
	private Path root;

	private Database database;
	private Map<Path, PyCode> pages = new HashMap<>();
	private Map<Path, FileTime> fileDates = new HashMap<>();

	private Path rewriteFile;
	private long rewriteFileLastUpdated = 0;
	private List<RewriteRule> rewriteRules = new ArrayList<>();

	private JadeConfiguration templates;

	private static final PythonInterpreter python = new PythonInterpreter(){{
		cflags = new CompilerFlags(CompilerFlags.PyCF_SOURCE_IS_UTF8);
	}};

	public VirtualHost(Path root){
		this.root = root;

		reload();
	}

	public void reload(){
		initRewrite("rewrite");
		initDatabase("database");
		initPython("pages");
		initJade("templates");
	}

	public String rewriteUrl(String url){
		for(RewriteRule r: rewriteRules)
			if (url.matches(r.getFrom())){
				String result = url.replaceAll(r.getFrom(), r.getTo());

				if (r.getOption().equals("R")) {
					return result;
				}

				return rewriteUrl(result);
			}

		return url;
	}

	public String dispatch(Request request, Response response){
		return new VirtualHostTask(this, request, response).dispatch();
	}

	public PyCode getPage(String url) throws IOException {
		Path pageFile = getFile("pages/" + url + ".page");

		if (Files.exists(pageFile)){
			if (!fileDates.containsKey(pageFile) || fileDates.get(pageFile) != Files.getLastModifiedTime(pageFile))
				compileFile(pageFile);

			return pages.get(pageFile);
		}

		return null;
	}

	public JadeConfiguration getTemplates(){
		return templates;
	}

	public Database getDatabase() {
		return database;
	}

	public Path getRoot(){
		return root;
	}

	public Path getFile(String path){
		return root.resolve(path);
	}

	private void compileFile(Path path) {
		try (BufferedReader reader = Files.newBufferedReader(path)) {
			PyCode code = python.compile(reader);
			pages.put(path, code);
			fileDates.put(path, Files.getLastModifiedTime(path));
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void initRewrite(String file){
		rewriteFile = getFile(file);
		updateRewriteEngine();
	}

	private void initDatabase(String dir){
		try {
			if (database == null){
				database = new Database(getFile(dir) + "/db");
				database.connect();
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	private void initPython(String dir){
		Py.getSystemState().path.append(new PyString(getFile("pages").toString()));
		parsePython(getFile(dir));
	}

	private void parsePython(Path directory) {
		try {
			Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
					if (!attrs.isDirectory() && file.getFileName().endsWith(".page")) {
						compileFile(file);
					}

					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void initJade(String dir){
		templates = new JadeConfiguration();
		templates.setCaching(true);
		templates.setPrettyPrint(true);
		templates.setTemplateLoader(new FileTemplateLoader(getFile(dir)));
	}

	public boolean updateRewriteEngine(){
		if (!Files.exists(rewriteFile))
			return false;

		try {
			if (Files.getLastModifiedTime(rewriteFile).toMillis() == rewriteFileLastUpdated)
				return true;

			rewriteRules.clear();
			Files.readAllLines(rewriteFile).forEach(line -> {
				if (line.startsWith("rewrite")){
					String params[] = line.split(" ");
					RewriteRule rl = new RewriteRule(params[1], params[2]);

					if (params.length > 3)
						rl.setOption(params[3]);

					rewriteRules.add(rl);
				}
			});

			rewriteFileLastUpdated = Files.getLastModifiedTime(rewriteFile).toMillis();
		} catch (IOException e) {
			e.printStackTrace();
		}

		return true;
	}
}
