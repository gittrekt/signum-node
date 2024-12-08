package brs.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.TextProgressMonitor;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.*;
import java.util.Comparator;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.concurrent.atomic.AtomicLong;

public class UpdateService {
    private static final Logger logger = LoggerFactory.getLogger(UpdateService.class);
    private static final String PHOENIX_RELEASES_URL = "https://api.github.com/repos/signum-network/phoenix/releases/latest";
    private static final String CLASSIC_REPO_URL = "https://github.com/signum-network/signum-classic-wallet.git";
    private static final String OPENAPI_SPEC_URL = "https://raw.githubusercontent.com/signum-network/signum-node/main/openapi/dist/signum-api.json";

    private static class ProgressBar {
        private final int width = 50; // Progress bar width in characters
        private long total;
        private long current;
        private String task;

        public ProgressBar(String task, long total) {
            this.task = task;
            this.total = total;
            this.current = 0;
        }

        public void update(long current) {
            this.current = current;
            int percentage = (int) ((current * 100) / total);
            int bars = (int) ((current * width) / total);
            
            StringBuilder builder = new StringBuilder("\r[");
            for (int i = 0; i < width; i++) {
                if (i < bars) builder.append("=");
                else if (i == bars) builder.append(">");
                else builder.append(" ");
            }
            builder.append("] ")
                   .append(String.format("%3d%%", percentage))
                   .append(" - ")
                   .append(task);
            
            System.out.print(builder);
            if (current >= total) {
                System.out.println();
            }
        }
    }

    private static void copyWithProgress(InputStream in, Path destination, long totalSize, String task) throws IOException {
        ProgressBar progress = new ProgressBar(task, totalSize);
        byte[] buffer = new byte[8192];
        long count = 0;
        int n;
        
        try (OutputStream out = Files.newOutputStream(destination)) {
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                count += n;
                progress.update(count);
            }
        }
    }

    private static void setupSSL() {
        try {
            // Create a trust manager that trusts all certificates
            javax.net.ssl.TrustManager[] trustAllCerts = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }
                    public void checkClientTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                    }
                    public void checkServerTrusted(
                        java.security.cert.X509Certificate[] certs, String authType) {
                    }
                }
            };

            // Install the all-trusting trust manager
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            javax.net.ssl.HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Trust all hosts
            javax.net.ssl.HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
            
            // Set TLS for Git
            System.setProperty("https.protocols", "TLSv1.2");
            
        } catch (Exception e) {
            logger.error("Error setting up SSL", e);
        }
    }

    public static void performUpdates() {
        try {
            setupSSL();
            logger.info("Starting wallet updates");
            updatePhoenixWallet();
            updateClassicWallet();
            updateOpenApi();
            logger.info("All updates completed successfully");
        } catch (Exception e) {
            logger.error("Error performing updates", e);
        }
    }

    private static HttpURLConnection createConnection(String urlString) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/vnd.github.v3+json");
        conn.setRequestProperty("User-Agent", "Signum-Node-Updater");
        
        if (conn instanceof javax.net.ssl.HttpsURLConnection) {
            ((javax.net.ssl.HttpsURLConnection) conn).setHostnameVerifier(
                (hostname, session) -> hostname.contains("github.com")
            );
        }
        
        return conn;
    }

    private static void updatePhoenixWallet() throws IOException {
        String phoenixDir = "./html/ui/phoenix";
        File phoenixDirFile = new File(phoenixDir);
        if (!phoenixDirFile.exists()) {
            if (!phoenixDirFile.mkdirs()) {
                throw new IOException("Failed to create directory: " + phoenixDir);
            }
        }

        Path tempDir = Files.createTempDirectory("phoenix-update");
        
        try {
            logger.debug("Downloading Phoenix wallet release information");
            HttpURLConnection conn = createConnection(PHOENIX_RELEASES_URL);
            
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                
                JsonObject release = JsonParser.parseString(response.toString()).getAsJsonObject();
                JsonArray assets = release.getAsJsonArray("assets");
                String downloadUrl = null;
                
                for (int i = 0; i < assets.size(); i++) {
                    JsonObject asset = assets.get(i).getAsJsonObject();
                    String name = asset.get("name").getAsString();
                    if (name.matches("web-phoenix-signum-wallet.*\\.zip")) {
                        downloadUrl = asset.get("browser_download_url").getAsString();
                        break;
                    }
                }
                
                if (downloadUrl == null) {
                    throw new IOException("Could not find Phoenix web wallet in latest release");
                }
                
                logger.debug("Starting Phoenix wallet download");
                HttpURLConnection downloadConn = (HttpURLConnection) new URL(downloadUrl).openConnection();
                long fileSize = downloadConn.getContentLengthLong();
                
                Path zipPath = tempDir.resolve("phoenix.zip");
                try (InputStream in = downloadConn.getInputStream()) {
                    copyWithProgress(in, zipPath, fileSize, "Downloading Phoenix wallet");
                }
                
                logger.debug("Extracting Phoenix wallet files");
                long totalEntries = countZipEntries(zipPath);
                long currentEntry = 0;
                ProgressBar extractProgress = new ProgressBar("Extracting Phoenix wallet", totalEntries);
                
                try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        Path destPath = tempDir.resolve(entry.getName());
                        if (entry.isDirectory()) {
                            Files.createDirectories(destPath);
                        } else {
                            Files.createDirectories(destPath.getParent());
                            Files.copy(zis, destPath, StandardCopyOption.REPLACE_EXISTING);
                        }
                        zis.closeEntry();
                        currentEntry++;
                        extractProgress.update(currentEntry);
                    }
                }
                
                logger.debug("Updating base href in index.html");
                
                Path indexPath = tempDir.resolve("dist/index.html");
                String content = Files.readString(indexPath);
                content = content.replaceAll(
                    Pattern.quote("<base href=\"/\">"),
                    "<base href=\"/phoenix/\">"
                );
                Files.write(indexPath, content.getBytes());
                
                logger.debug("Copying Phoenix wallet files to final location");
                
                if (Files.exists(Path.of(phoenixDir))) {
                    Files.walk(Path.of(phoenixDir))
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }
                
                Path distDir = tempDir.resolve("dist");
                long totalFiles = Files.walk(distDir).count();
                ProgressBar copyProgress = new ProgressBar("Copying Phoenix wallet files", totalFiles);
                final AtomicLong currentFile = new AtomicLong(0);
                
                Files.walk(distDir).forEach(source -> {
                    Path destination = Path.of(phoenixDir).resolve(distDir.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        logger.error("Error copying file: {}", source, e);
                    }
                    copyProgress.update(currentFile.incrementAndGet());
                });
            }
            
            logger.info("Phoenix wallet update completed");
        } catch (Exception e) {
            logger.error("Failed to update Phoenix wallet", e);
            throw new IOException("Phoenix wallet update failed", e);
        } finally {
            logger.debug("Cleaning up temporary files");
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    private static void updateClassicWallet() throws IOException {
        String classicDir = "./html/ui/classic";
        File classicDirFile = new File(classicDir);
        if (!classicDirFile.exists()) {
            if (!classicDirFile.mkdirs()) {
                throw new IOException("Failed to create directory: " + classicDir);
            }
        }

        Path tempDir = Files.createTempDirectory("classic-update");
        
        try {
            logger.debug("Cloning Classic wallet repository");
            
            try (Git git = Git.cloneRepository()
                    .setURI(CLASSIC_REPO_URL)
                    .setDirectory(tempDir.toFile())
                    .setDepth(1)
                    .setProgressMonitor(new TextProgressMonitor())
                    .call()) {

                Path srcDir = tempDir.resolve("src");
                if (!Files.exists(srcDir)) {
                    throw new IOException("Source directory not found in classic wallet repository");
                }

                if (Files.exists(Path.of(classicDir))) {
                    Files.walk(Path.of(classicDir))
                        .sorted(Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(File::delete);
                }

                long totalFiles = Files.walk(srcDir).count();
                ProgressBar copyProgress = new ProgressBar("Copying Classic wallet files", totalFiles);
                final AtomicLong currentFile = new AtomicLong(0);

                Files.walk(srcDir).forEach(source -> {
                    Path destination = Path.of(classicDir).resolve(srcDir.relativize(source));
                    try {
                        if (Files.isDirectory(source)) {
                            Files.createDirectories(destination);
                        } else {
                            Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
                        }
                    } catch (IOException e) {
                        logger.error("Error copying file: {}", source, e);
                    }
                    copyProgress.update(currentFile.incrementAndGet());
                });
            } catch (GitAPIException e) {
                throw new IOException("Failed to clone Classic wallet repository", e);
            }
            
            logger.info("Classic wallet update completed");
        } catch (Exception e) {
            logger.error("Failed to update Classic wallet", e);
            throw new IOException("Classic wallet update failed", e);
        } finally {
            logger.debug("Cleaning up temporary files");
            Files.walk(tempDir)
                .sorted(Comparator.reverseOrder())
                .map(Path::toFile)
                .forEach(File::delete);
        }
    }

    private static void updateOpenApi() throws IOException {
        String openApiDir = "./html/api-doc";
        if (!new File(openApiDir).exists()) {
            throw new IOException("Cannot find " + openApiDir);
        }

        logger.debug("Downloading API specification");
        URL url = new URL(OPENAPI_SPEC_URL);
        Path destination = Path.of(openApiDir, "signum-api.json");
        
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        long fileSize = conn.getContentLengthLong();
        
        try (InputStream in = conn.getInputStream()) {
            copyWithProgress(in, destination, fileSize, "Downloading API specification");
        }
        
        logger.info("API documentation update completed");
    }

    private static long countZipEntries(Path zipPath) throws IOException {
        long count = 0;
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zipPath))) {
            while (zis.getNextEntry() != null) {
                count++;
            }
        }
        return count;
    }
}