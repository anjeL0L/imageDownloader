import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ImageDownloader {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final long MAX_IMAGE_SIZE_BYTES = 10L * 1024 * 1024; // 10 MB
    private static final int MAX_REDIRECTS = 5;
    private static final String USER_AGENT = "ImageDownloader-Java/1.0";

    private record RegistroDescarga(String urlImagen, String archivoLocal, String estado, long tamanoBytes) {}
    private static final List<RegistroDescarga> historialDescargas = new ArrayList<>();

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Uso: java ImageDownloader <URL> [directorio_salida]");
            System.err.println("Ejemplo: java ImageDownloader https://ejemplo.org/galeria.html ./imagenes");
            return;
        }

        Path directorioSalida = (args.length == 2) ? Path.of(args[1]) : Path.of("imagenes");

        System.out.println("ImageDownloader");
        System.out.println("---------------------------------------");

        URL urlPagina = validarURL(args[0]);
        if (urlPagina == null) {
            return;
        }

        System.out.println("Conectando con: " + urlPagina);
        System.out.println();

        String html = obtenerHTML(urlPagina);
        if (html == null) {
            return;
        }

        System.out.println();
        System.out.println("Analizando documento HTML...");
        System.out.println();

        Set<String> referencias = extraerImagenes(html);
        System.out.println("Imágenes encontradas: " + referencias.size());
        System.out.println();

        if (referencias.isEmpty()) {
            System.out.println("No se encontraron etiquetas <img> con atributo src válido.");
            return;
        }

        try {
            Files.createDirectories(directorioSalida);
        } catch (IOException e) {
            System.err.println("Error al crear el directorio '" + directorioSalida + "': " + e.getMessage());
            return;
        }

        int exitosas = 0;
        int fallidas = 0;
        int total = referencias.size();
        int indice = 0;

        for (String referencia : referencias) {
            indice++;
            URL urlImagen = resolverImagen(urlPagina, referencia);

            if (urlImagen == null) {
                imprimirEstado(indice, total, referencia, "ERROR (URL o esquema no admitido)");
                historialDescargas.add(new RegistroDescarga(referencia, "—", "URL/esquema no admitido", 0));
                fallidas++;
                continue;
            }

            if (descargarImagen(urlImagen, directorioSalida, indice, total)) {
                exitosas++;
            } else {
                fallidas++;
            }
        }

        System.out.println();
        System.out.println("---------------------------------------");
        System.out.println("Descargas exitosas: " + exitosas);
        System.out.println("Descargas fallidas: " + fallidas);
        System.out.println("Directorio: " + directorioSalida.toAbsolutePath().normalize());

        generarReporteCSV(directorioSalida);
    }

    public static URL validarURL(String argumento) {
        if (argumento == null || argumento.isBlank()) {
            System.err.println("Error: La URL proporcionada está vacía.");
            return null;
        }

        try {
            URI uri = URI.create(argumento.trim());
            String esquema = uri.getScheme();

            if (esquema == null || (!esquema.equalsIgnoreCase("http") && !esquema.equalsIgnoreCase("https"))) {
                System.err.println("Error: Protocolo no admitido (" + esquema + "). Solo se permiten HTTP y HTTPS.");
                return null;
            }

            URL url = uri.toURL();
            if (url.getHost() == null || url.getHost().isBlank()) {
                System.err.println("Error: La URL no contiene un servidor válido.");
                return null;
            }

            return url;
        } catch (IllegalArgumentException e) {
            System.err.println("Error: URL inválida -> " + e.getMessage());
            return null;
        } catch (MalformedURLException e) {
            System.err.println("Error: URL mal formada -> " + e.getMessage());
            return null;
        }
    }

    private static HttpURLConnection abrirConexionHttp(URL urlInicial) throws IOException {
        URL urlActual = urlInicial;

        for (int salto = 0; salto <= MAX_REDIRECTS; salto++) {
            URLConnection rawConn = urlActual.openConnection();
            if (!(rawConn instanceof HttpURLConnection connection)) {
                throw new IOException("La conexión no corresponde a HTTP/HTTPS.");
            }

            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "*/*");

            int codigo = connection.getResponseCode();

            if (codigo == HttpURLConnection.HTTP_MOVED_PERM
                    || codigo == HttpURLConnection.HTTP_MOVED_TEMP
                    || codigo == HttpURLConnection.HTTP_SEE_OTHER
                    || codigo == 307
                    || codigo == 308) {

                String location = connection.getHeaderField("Location");
                connection.disconnect();

                if (location == null || location.isBlank()) {
                    throw new IOException("Redirección HTTP " + codigo + " sin encabezado Location.");
                }

                URL siguienteUrl = resolverImagen(urlActual, location);
                if (siguienteUrl == null) {
                    throw new IOException("Redirección hacia protocolo o destino no permitido: " + location);
                }
                urlActual = siguienteUrl;
                continue;
            }

            return connection;
        }

        throw new IOException("Se excedió el máximo de redirecciones (" + MAX_REDIRECTS + ").");
    }

    public static String obtenerHTML(URL url) {
        HttpURLConnection connection = null;
        try {
            connection = abrirConexionHttp(url);
            int codigo = connection.getResponseCode();
            String contentType = connection.getContentType();

            System.out.println("Código HTTP: " + codigo);
            System.out.println("Tipo de contenido: " + (contentType != null ? contentType : "desconocido"));

            if (codigo < 200 || codigo >= 300) {
                System.err.println("Error: El servidor respondió con código HTTP " + codigo + ".");
                return null;
            }

            if (contentType != null) {
                String tipoBase = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
                if (!tipoBase.equals("text/html") && !tipoBase.equals("application/xhtml+xml") && !tipoBase.startsWith("text/")) {
                    System.err.println("Error: El recurso no es un documento HTML (" + tipoBase + ").");
                    return null;
                }
            }

            Charset charset = extraerCharset(contentType);
            StringBuilder htmlBuilder = new StringBuilder();

            try (InputStream input = connection.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(input, charset))) {
                String linea;
                while ((linea = reader.readLine()) != null) {
                    htmlBuilder.append(linea).append(System.lineSeparator());
                }
            }

            return htmlBuilder.toString();

        } catch (SocketTimeoutException e) {
            System.err.println("Error: Tiempo de espera agotado -> " + e.getMessage());
            return null;
        } catch (IOException e) {
            System.err.println("Error de comunicación al obtener HTML -> " + e.getMessage());
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Charset extraerCharset(String contentType) {
        if (contentType == null) {
            return StandardCharsets.UTF_8;
        }
        Pattern charsetPattern = Pattern.compile("charset\\s*=\\s*[\"']?([^\\s;\"']+)[\"']?", Pattern.CASE_INSENSITIVE);
        Matcher matcher = charsetPattern.matcher(contentType);
        if (matcher.find()) {
            try {
                return Charset.forName(matcher.group(1));
            } catch (IllegalCharsetNameException | UnsupportedCharsetException ignored) {
            }
        }
        return StandardCharsets.UTF_8;
    }

    public static Set<String> extraerImagenes(String html) {
        Set<String> imagenes = new LinkedHashSet<>();
        if (html == null || html.isBlank()) {
            return imagenes;
        }

        Pattern imgPattern = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
        Pattern srcPattern = Pattern.compile("\\bsrc\\s*=\\s*([\"'])(.*?)\\1", Pattern.CASE_INSENSITIVE);

        Matcher imgMatcher = imgPattern.matcher(html);
        while (imgMatcher.find()) {
            String etiqueta = imgMatcher.group();
            Matcher srcMatcher = srcPattern.matcher(etiqueta);
            if (srcMatcher.find()) {
                String src = srcMatcher.group(2).trim();
                if (!src.isEmpty()) {
                    imagenes.add(src);
                }
            }
        }

        return imagenes;
    }

    public static URL resolverImagen(URL base, String referencia) {
        if (base == null || referencia == null || referencia.isBlank()) {
            return null;
        }

        String refLimpia = referencia.trim();
        String refMinusculas = refLimpia.toLowerCase(Locale.ROOT);

        if (refMinusculas.startsWith("data:")
                || refMinusculas.startsWith("javascript:")
                || refMinusculas.startsWith("mailto:")
                || refMinusculas.startsWith("blob:")
                || refMinusculas.startsWith("file:")) {
            return null;
        }

        try {
            URI baseUri = base.toURI();
            if (baseUri.getRawPath() == null || baseUri.getRawPath().isEmpty()) {
                baseUri = new URI(
                        baseUri.getScheme(),
                        baseUri.getRawAuthority(),
                        "/",
                        baseUri.getRawQuery(),
                        baseUri.getRawFragment()
                );
            }

            String refCodificada = refLimpia.replace(" ", "%20");
            URI resueltaUri = baseUri.resolve(refCodificada);
            String esquema = resueltaUri.getScheme();

            if (esquema == null || (!esquema.equalsIgnoreCase("http") && !esquema.equalsIgnoreCase("https"))) {
                return null;
            }

            URL urlFinal = resueltaUri.toURL();
            if (urlFinal.getHost() == null || urlFinal.getHost().isBlank()) {
                return null;
            }

            return urlFinal;
        } catch (URISyntaxException | IllegalArgumentException | MalformedURLException e) {
            return null;
        }
    }

    public static boolean descargarImagen(URL url, Path directorio, int numero) {
        return descargarImagen(url, directorio, numero, numero);
    }

    public static boolean descargarImagen(URL url, Path directorio, int numero, int total) {
        HttpURLConnection connection = null;
        Path archivoDestino = null;
        boolean descargaCompletada = false;

        try {
            connection = abrirConexionHttp(url);
            int codigo = connection.getResponseCode();

            if (codigo < 200 || codigo >= 300) {
                imprimirEstado(numero, total, url.toString(), "ERROR (HTTP " + codigo + ")");
                historialDescargas.add(new RegistroDescarga(url.toString(), "—", "Error HTTP " + codigo, 0));
                return false;
            }

            String contentType = connection.getContentType();
            String extension = obtenerExtensionDesdeMime(contentType);

            if (extension == null) {
                String tipoReportado = (contentType != null) ? contentType : "desconocido";
                imprimirEstado(numero, total, url.toString(), "ERROR (No es imagen: " + tipoReportado + ")");
                historialDescargas.add(new RegistroDescarga(url.toString(), "—", "Tipo no admitido: " + tipoReportado, 0));
                return false;
            }

            long contentLength = connection.getContentLengthLong();
            if (contentLength > MAX_IMAGE_SIZE_BYTES) {
                imprimirEstado(numero, total, url.toString(), "ERROR (Excede límite de 10 MB)");
                historialDescargas.add(new RegistroDescarga(url.toString(), "—", "Excede 10 MB", 0));
                return false;
            }

            String nombreArchivo = String.format("imagen_%03d%s", numero, extension);
            archivoDestino = directorio.resolve(nombreArchivo);

            if (Files.exists(archivoDestino)) {
                imprimirEstado(numero, total, nombreArchivo, "OMITIDO (archivo ya existe)");
                historialDescargas.add(new RegistroDescarga(url.toString(), nombreArchivo, "Omitido (ya existe)", 0));
                return false;
            }

            long bytesLeidosTotales = 0;
            byte[] buffer = new byte[8192];

            try (InputStream input = connection.getInputStream();
                 OutputStream output = Files.newOutputStream(archivoDestino, StandardOpenOption.CREATE_NEW)) {

                int bytesLeidos;
                while ((bytesLeidos = input.read(buffer)) != -1) {
                    bytesLeidosTotales += bytesLeidos;
                    if (bytesLeidosTotales > MAX_IMAGE_SIZE_BYTES) {
                        throw new IOException("Superó el límite máximo de 10 MB.");
                    }
                    output.write(buffer, 0, bytesLeidos);
                }
            }

            descargaCompletada = true;
            imprimirEstado(numero, total, nombreArchivo, "OK (" + bytesLeidosTotales + " bytes)");
            historialDescargas.add(new RegistroDescarga(url.toString(), nombreArchivo, "Correcto", bytesLeidosTotales));
            return true;

        } catch (FileAlreadyExistsException e) {
            String nombre = (archivoDestino != null) ? archivoDestino.getFileName().toString() : url.toString();
            imprimirEstado(numero, total, nombre, "OMITIDO (archivo ya existe)");
            historialDescargas.add(new RegistroDescarga(url.toString(), nombre, "Omitido (ya existe)", 0));
            return false;
        } catch (SocketTimeoutException e) {
            imprimirEstado(numero, total, url.toString(), "ERROR (Tiempo de espera agotado)");
            historialDescargas.add(new RegistroDescarga(url.toString(), "—", "Timeout", 0));
            return false;
        } catch (IOException e) {
            imprimirEstado(numero, total, url.toString(), "ERROR (" + e.getMessage() + ")");
            historialDescargas.add(new RegistroDescarga(url.toString(), "—", "Error: " + e.getMessage(), 0));
            return false;
        } finally {
            if (!descargaCompletada && archivoDestino != null) {
                try {
                    Files.deleteIfExists(archivoDestino);
                } catch (IOException ignored) {
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String obtenerExtensionDesdeMime(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String mime = contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        if (!mime.startsWith("image/")) {
            return null;
        }
        return switch (mime) {
            case "image/png" -> ".png";
            case "image/jpeg", "image/jpg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "image/bmp" -> ".bmp";
            case "image/x-icon", "image/vnd.microsoft.icon" -> ".ico";
            case "image/avif" -> ".avif";
            default -> {
                String subtipo = mime.substring("image/".length()).replaceAll("[^a-z0-9]", "");
                yield subtipo.isEmpty() ? ".img" : "." + subtipo;
            }
        };
    }

    private static void imprimirEstado(int numero, int total, String etiqueta, String estado) {
        System.out.printf("[%d/%d] %-30s ............... %s%n", numero, total, etiqueta, estado);
    }

    private static void generarReporteCSV(Path directorioSalida) {
        if (historialDescargas.isEmpty()) {
            return;
        }
        Path rutaCsv = directorioSalida.resolve("descargas.csv");
        List<String> lineas = new ArrayList<>();
        lineas.add("URL de imagen,Archivo local,Estado,Tamaño (bytes)");

        for (RegistroDescarga reg : historialDescargas) {
            String urlEscapada = "\"" + reg.urlImagen().replace("\"", "\"\"") + "\"";
            String archivoEscapado = "\"" + reg.archivoLocal().replace("\"", "\"\"") + "\"";
            String estadoEscapado = "\"" + reg.estado().replace("\"", "\"\"") + "\"";
            lineas.add(String.format("%s,%s,%s,%d",
                    urlEscapada, archivoEscapado, estadoEscapado, reg.tamanoBytes()));
        }

        try {
            Files.write(rutaCsv, lineas, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("Reporte CSV generado en: " + rutaCsv.toAbsolutePath().normalize());
        } catch (IOException e) {
            System.err.println("No se pudo generar descargas.csv: " + e.getMessage());
        }
    }
}