import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    // Dev only: the browser talks to Vite (5173), and Vite forwards /api to Spring (8080).
    // The browser only ever sees one origin, so the API still needs no CORS config.
    proxy: { '/api': 'http://localhost:8080' },
  },
  build: {
    // Straight into Spring Boot's classpath static folder, so the jar serves it at "/".
    // `mvnw package` runs this via frontend-maven-plugin (see pom.xml).
    outDir: '../target/classes/static',
    emptyOutDir: true,
  },
});
