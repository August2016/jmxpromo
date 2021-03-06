package io.prometheus.jmx;

import org.junit.Ignore;
import org.junit.Test;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import static org.hamcrest.MatcherAssert.assertThat;

public class JavaAgentAttachIT {
    private List<URL> getClassloaderUrls() {
        return getClassloaderUrls(getClass().getClassLoader());
    }

    private static List<URL> getClassloaderUrls(ClassLoader classLoader) {
        if (classLoader == null) {
            return Collections.emptyList();
        }
        if (!(classLoader instanceof URLClassLoader)) {
            return getClassloaderUrls(classLoader.getParent());
        }
        URLClassLoader u = (URLClassLoader) classLoader;
        List<URL> result = new ArrayList<URL>(Arrays.asList(u.getURLs()));
        result.addAll(getClassloaderUrls(u.getParent()));
        return result;
    }

    private String buildClasspath() {
        StringBuilder sb = new StringBuilder();
        for (URL url : getClassloaderUrls()) {
            if (!url.getProtocol().equals("file")) {
                continue;
            }
            if (sb.length() != 0) {
                sb.append(':');
            }
            sb.append(url.getPath());
        }
        return sb.toString();
    }

    @Test
    public void agentLoads() throws IOException, InterruptedException {
        // If not starting the testcase via Maven, set the buildDirectory and finalName system properties manually.
        final String buildDirectory = (String) System.getProperties().get("buildDirectory");
        final String finalName = (String) System.getProperties().get("finalName");
        final int port = Integer.parseInt((String) System.getProperties().get("it.port"));
        final String config = getClass().getClassLoader().getResource("test.yml").getFile();

        final String javaHome = System.getenv("JAVA_HOME");
        final String java;
        if (javaHome != null && javaHome.equals("")) {
            java = javaHome + "/bin/java";
        } else {
            java = "java";
        }

        final Process app = new ProcessBuilder()
            .command(java, "-cp", buildClasspath(), "io.prometheus.jmx.TestApplication")
            .start();
        try {
            // Wait for application to start
            String pid = new BufferedReader(new InputStreamReader(app.getInputStream())).readLine();
            inheritIO(app.getInputStream(), System.out);

            String classpath = buildClasspath() + File.pathSeparator +
              System.getProperty("java.home") + "/../lib/tools.jar" + File.pathSeparator +
              buildDirectory + "/" + finalName + ".jar";



            Process attachProcess = new ProcessBuilder()
                .command(java, "-cp",
                    classpath,
                    "io.prometheus.jmx.shaded.io.prometheus.jmx.Attach",
                    pid,
                    port + ":" + config)
                .start();
            Thread.sleep(1000);
            inheritIO(attachProcess.getInputStream(), System.out);
            inheritIO(attachProcess.getErrorStream(), System.out);
            inheritIO(app.getInputStream(), System.out);
            System.out.println(port);
            URL uri = new URL("http://localhost:" + port + "/metrics");
            HttpURLConnection cnx = (HttpURLConnection) uri.openConnection();
            InputStream stream = cnx.getInputStream();
            BufferedReader contents = new BufferedReader(new InputStreamReader(stream));
            boolean found = false;
            System.out.printf("found");
            while (!found) {
                String line = contents.readLine();
                if (line == null) {
                    break;
                }
                if (line.contains("jmx_scrape_duration_seconds")) {
                    found = true;
                }
            }

            assertThat("Expected metric not found", found);
                // Tell application to stop
            cnx.disconnect();
            app.getOutputStream().write('\n');
            try {
                app.getOutputStream().flush();
            } catch (IOException ignored) {
            }
        } catch (Throwable ex) {
            assertThat("Expection caught during scraping", false);
            ex.printStackTrace();
        } finally {
            final int exitcode = app.waitFor();
            // Log any errors printed
            int len;
            byte[] buffer = new byte[100];
            while ((len = app.getErrorStream().read(buffer)) != -1) {
                System.out.write(buffer, 0, len);
            }

            assertThat("Application did not exit cleanly", exitcode == 0);
        }
    }

    private static void inheritIO(final InputStream src, final PrintStream dest) {
        new Thread(new Runnable() {
            public void run() {
                Scanner sc = new Scanner(src);
                while (sc.hasNextLine()) {
                    dest.println(sc.nextLine());
                }
            }
        }).start();
    }
}
