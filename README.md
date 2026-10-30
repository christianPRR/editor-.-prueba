# Editor IA (Android)

Editor de imágenes para Android con IA.

## Funciones
- **Borrar objetos**: pintas sobre el objeto y la IA rellena el hueco (modelo LaMa en el teléfono, sin internet). Sin el modelo funciona en modo básico.
- **Quitar fondo**: con ML Kit (la primera vez necesita internet para bajar el modelo).
- **Expandir imagen**: con IA usando la API de Stability AI (necesitas una clave). Sin clave, rellena con fondo difuminado.
- Ajustes (brillo, contraste, saturación), filtros, recorte por proporción, girar, deshacer y guardar en la galería.

## Cómo obtener el APK con GitHub
1. Sube todo este proyecto a un repositorio de GitHub (rama `main`).
2. Entra a la pestaña **Actions**. El flujo **Compilar APK** se ejecuta solo al subir los archivos (o con **Run workflow**).
3. Cuando termine (unos 5-10 min), abre la ejecución y descarga **EditorIA-apk** al final de la página.
4. Descomprime el zip y pasa `app-debug.apk` a tu teléfono. Permite "instalar apps desconocidas" y ábrelo.

## Primer uso
- Toca ⚙ para poner tu clave de Stability AI (opcional, solo para expandir con IA).
- En la herramienta **Borrar**, pulsa "Descargar modelo de IA" una sola vez (~200 MB) para obtener la mejor calidad.

## Si la compilación falla
Copia el error rojo del paso "Compilar APK (debug)" y envíamelo para corregirlo.
