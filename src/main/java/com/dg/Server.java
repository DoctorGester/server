package com.dg;

import com.dg.sites.DgPic;
import net.freeutils.httpserver.HTTPServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * @author doc
 */
public class Server {
    private static Logger log = LoggerFactory.getLogger(Server.class);

    public static void main(String[] args) {
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
