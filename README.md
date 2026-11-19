# ImageDownloader

Aplicación Java de línea de comandos (JDK 17+) que descarga el documento HTML de una página web mediante `URL` y `HttpURLConnection`, identifica las imágenes referenciadas en etiquetas `<img>`, resuelve rutas relativas y guarda cada imagen localmente validando su tipo MIME (`image/*`) y tamaño máximo (10 MB).

## Requisitos y ejecución

- **Requisitos:** JDK 17 o superior (sin bibliotecas externas).
- **Compilación:**
  ```bash
  mkdir -p out
  javac -encoding UTF-8 -d out src/main/java/ImageDownloader.java

  Ejecución:Bash# Directorio por defecto (./imagenes/)
java -cp out ImageDownloader [https://ejemplo.org/galeria.html](https://ejemplo.org/galeria.html)

# Directorio de salida personalizado + generación de reporte descargas.csv
java -cp out ImageDownloader [https://ejemplo.org/galeria.html](https://ejemplo.org/galeria.html) ./descargas
Limitaciones del análisis HTML con expresiones regularesNo captura atributos src sin comillas (ej. <img src=foto.png>).Detecta etiquetas <img> aunque estén dentro de comentarios HTML (<!-- ... -->).No procesa atributos modernos de carga diferida como data-src o srcset.No ejecuta JavaScript, por lo que ignora imágenes renderizadas dinámicamente en el cliente.Puede truncar la etiqueta si un atributo previo contiene el carácter > (ej. alt="a > b").Pruebas funcionalesCasoEntrada o condiciónResultado esperadoEstadoP01Sin argumentosMostrar instrucciones de usoVerificadoP02URL inválidaMostrar error controladoVerificadoP03Protocolo no admitido (ftp://)Rechazar direcciónVerificadoP04HTML sin imágenesReportar cero imágenesVerificadoP05URL absoluta de imagenDescargar correctamenteVerificadoP06URL relativa de imagenResolver y descargarVerificadoP07Imagen duplicadaDescargar una sola vezVerificadoP08Imagen inexistente (HTTP 404)Registrar error y continuarVerificadoP09Recurso que no es imagenRechazar descargaVerificadoP10Servidor no disponibleControlar excepciónVerificadoP11Archivo mayor a 10 MBCancelar descarga y borrar parcialVerificadoP12Archivo existenteEvitar sobrescrituraVerificadoEvidencias de pruebas funcionales1. Validación de argumentos y protocolos no admitidos (Casos P01 y P03)PlaintextPS C:\Users\angel\IdeaProjects\ImageDownloader> java -cp out ImageDownloader
Uso: java ImageDownloader <URL> [directorio_salida]
Ejemplo: java ImageDownloader [https://ejemplo.org/galeria.html](https://ejemplo.org/galeria.html) ./imagenes

PS C:\Users\angel\IdeaProjects\ImageDownloader> java -cp out ImageDownloader ftp://ejemplo.org/galeria.html
ImageDownloader
---------------------------------------
Error: Protocolo no admitido (ftp). Solo se permiten HTTP y HTTPS.
2. Descarga de imágenes absolutas, relativas, manejo de duplicados y errores (Casos P05, P06, P07, P08 y P09)PlaintextPS C:\Users\angel\IdeaProjects\ImageDownloader> java -cp out ImageDownloader http://localhost:8000/prueba.html
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8000/prueba.html

Código HTTP: 200
Tipo de contenido: text/html

Analizando documento HTML...

Imágenes encontradas: 5

[1/5] imagen_001.png                 ............... OK (5969 bytes)
[2/5] imagen_002.jpg                 ............... OK (542091 bytes)
[3/5] http://localhost:8000/inexistente.png ............... ERROR (HTTP 404)
[4/5] http://localhost:8000/prueba.html ............... ERROR (No es imagen: text/html)
[5/5] data:image/png;base64,iVBORw0KGgo= ............... ERROR (URL o esquema no admitido)

---------------------------------------
Descargas exitosas: 2
Descargas fallidas: 3
Directorio: C:\Users\angel\IdeaProjects\ImageDownloader\imagenes
Reporte CSV generado en: C:\Users\angel\IdeaProjects\ImageDownloader\imagenes\descargas.csv
3. Protección contra sobrescritura de archivos existentes (Caso P12)PlaintextPS C:\Users\angel\IdeaProjects\ImageDownloader> java -cp out ImageDownloader http://localhost:8000/prueba.html
ImageDownloader
---------------------------------------
Conectando con: http://localhost:8000/prueba.html

Código HTTP: 200
Tipo de contenido: text/html

Analizando documento HTML...

Imágenes encontradas: 5

[1/5] imagen_001.png                 ............... OMITIDO (archivo ya existe)
[2/5] imagen_002.jpg                 ............... OMITIDO (archivo ya existe)
[3/5] http://localhost:8000/inexistente.png ............... ERROR (HTTP 404)
[4/5] http://localhost:8000/prueba.html ............... ERROR (No es imagen: text/html)
[5/5] data:image/png;base64,iVBORw0KGgo= ............... ERROR (URL o esquema no admitido)

---------------------------------------
Descargas exitosas: 0
Descargas fallidas: 5
Directorio: C:\Users\angel\IdeaProjects\ImageDownloader\imagenes
Reporte CSV generado en: C:\Users\angel\IdeaProjects\ImageDownloader\imagenes\descargas.csv
Preguntas de reflexión¿Qué responsabilidad desempeña la aplicación dentro de una arquitectura cliente-servidor?
Actúa como cliente HTTP: envía peticiones GET al servidor web, interpreta los códigos de estado y encabezados, analiza el HTML recibido y realiza solicitudes adicionales para descargar los recursos binarios referenciados.¿Qué diferencias existen entre URL, URLConnection y HttpURLConnection?URL representa únicamente la dirección lógica del recurso; URLConnection es la clase abstracta que gestiona la configuración y lectura genérica de la conexión; y HttpURLConnection es su subclase especializada en HTTP/HTTPS que añade métodos como getResponseCode(), setRequestMethod() y disconnect().¿Qué sucede al invocar openConnection()?
Se instancia localmente el objeto de conexión correspondiente al protocolo (HttpURLConnection), pero aún no se abre ningún socket de red ni se envían datos al servidor.¿Qué operaciones provocan que la conexión se establezca realmente?
La comunicación de red inicia al llamar a connect() o a métodos que requieren leer la respuesta del servidor, como getInputStream(), getResponseCode() o getContentType().¿Por qué una página HTML puede requerir múltiples conexiones?
Porque el HTML solo contiene texto y enlaces (src) hacia recursos externos; el cliente debe descargar primero el HTML y luego abrir una conexión independiente por cada imagen referenciada.¿Cuál es la diferencia entre getInputStream() y getContentType()?getContentType() lee el encabezado HTTP Content-Type para identificar el tipo MIME y codificación del recurso, mientras que getInputStream() proporciona el flujo de bytes del cuerpo de la respuesta.¿Por qué distinguir recursos textuales y binarios?
Los recursos textuales requieren decodificación de caracteres (UTF-8, ISO-8859-1) mediante lectores de texto, mientras que los binarios (imágenes) deben copiarse como bytes exactos para no corromper el archivo.¿Para qué sirven los tiempos máximos de conexión y lectura?
Evitan que la aplicación quede bloqueada indefinidamente si un servidor no responde al iniciar la conexión (setConnectTimeout) o deja de enviar datos a mitad de la descarga (setReadTimeout).¿Qué limitaciones tiene analizar HTML mediante expresiones regulares?
HTML no es un lenguaje regular; las expresiones regulares ignoran la jerarquía del DOM y fallan ante etiquetas comentadas, atributos sin comillas, saltos de línea, signos > dentro de atributos y contenido generado por JavaScript.¿Cómo podría implementarse la descarga concurrente usando únicamente la API estándar de Java?
Creando un pool de hilos con Executors.newFixedThreadPool(...), enviando cada descarga como tarea Callable o CompletableFuture, sincronizando los contadores con AtomicInteger y cerrando el ejecutor con shutdown().
