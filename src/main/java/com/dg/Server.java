package com.dg;

import com.dg.sites.DgPic;
import net.freeutils.httpserver.HTTPServer;
import org.pmw.tinylog.Configurator;
import org.pmw.tinylog.Level;
import org.pmw.tinylog.labelers.TimestampLabeler;
import org.pmw.tinylog.policies.DailyPolicy;
import org.pmw.tinylog.writers.ConsoleWriter;
import org.pmw.tinylog.writers.RollingFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * @author doc
 */
public class Server {
    private static Logger log = LoggerFactory.getLogger(Server.class);

    private static void setupLogging() {
        Configurator.defaultConfig()
                .level(Level.INFO)
                .formatPattern("[{date:yyyy-MM-dd HH:mm:ss,SSS}] [{thread}] {level} {class_name} - {message}")
                .writer(new ConsoleWriter())
                .addWriter(new RollingFileWriter("logs/server.log", 90, new TimestampLabeler("yyyy-MM-dd"), new DailyPolicy()))
                .activate();
    }

    public static void main(String[] args) {
        setupLogging();

        log.info("Starting up");

        int port = 80;
        boolean isLocal = false;

        for (String arg : args) {
            if (arg.matches("\\d{1,4}")) {
                port = Integer.valueOf(arg);
            } else if ("local".equals(arg)) {
                isLocal = true;
            }
        }

        final HTTPServer server = new HTTPServer(port);
        server.setExecutor(new ThreadPoolExecutor(8, Integer.MAX_VALUE, 300, TimeUnit.SECONDS, new SynchronousQueue<>()));

        try {
            server.start();
        } catch (final IOException e) {
            log.error("Error when starting server", e);
            return;
        }

        // TODO handle other hosts
        if (isLocal) {
            DgPic.ignite(server.getVirtualHost(null));
        } else {
            final HTTPServer.VirtualHost host = new HTTPServer.VirtualHost("dg-pic.tk");
            server.addVirtualHost(host);

            DgPic.ignite(host);
        }
    }
}
