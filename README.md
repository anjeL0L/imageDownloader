# ImageDownloader

Aplicación Java de línea de comandos (JDK 17+) que descarga el documento HTML de una página web mediante `URL` y `HttpURLConnection`, identifica las imágenes referenciadas en etiquetas `<img>`, resuelve rutas relativas y guarda cada imagen localmente validando su tipo MIME (`image/*`) y tamaño máximo (10 MB).

## Requisitos y ejecución

- **Requisitos:** JDK 17 o superior (sin bibliotecas externas).
- **Compilación:**
  ```bash
  mkdir -p out
  javac -encoding UTF-8 -d out src/main/java/ImageDownloader.java