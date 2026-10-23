# Siirt Fıstığı — Java (Spring Boot) sunucusu için Docker imajı.
# Render, Railway, Fly.io gibi platformlar bu dosyayla derleyip çalıştırır.
#
#   docker build -t siirt-fistik .
#   docker run -p 8000:8000 -e DATABASE_URL=postgresql://kullanici:sifre@sunucu:5432/veritabani?sslmode=require \
#              -e JWT_SECRET=<en-az-32-karakter> siirt-fistik

# ─── 1) Derleme ───
FROM maven:3.9.9-eclipse-temurin-21 AS derleme
WORKDIR /kaynak
COPY java-backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY java-backend/src ./src
RUN mvn -q -B -DskipTests package

# ─── 2) Çalıştırma (yalnızca JRE; derleme araçları imaja girmez) ───
FROM eclipse-temurin:21-jre
WORKDIR /uygulama
RUN useradd --system --uid 1001 fistik
COPY --from=derleme /kaynak/target/siirt-fistik.jar uygulama.jar
USER fistik

# Ücretsiz planlardaki düşük belleğe (512 MB) uygun JVM ayarları
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1" \
    PORT=8000
EXPOSE 8000
CMD ["java", "-jar", "uygulama.jar"]
