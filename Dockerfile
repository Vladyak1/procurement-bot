# Этап сборки
FROM maven:3.9.9-eclipse-temurin-21 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src/ ./src/
RUN mvn clean package -DskipTests

# Этап выполнения
FROM eclipse-temurin:21-jre
WORKDIR /app

# Российский Национальный УЦ (Минцифры): torgi.gov.ru использует сертификат
# Russian Trusted Sub CA, корня которого нет в стандартном Java-truststore
# (иначе PKIX path building failed). Импортируем корневой и промежуточный CA.
COPY certs/russian_trusted_root_ca.crt certs/russian_trusted_sub_ca.crt /tmp/
RUN keytool -importcert -noprompt -trustcacerts -alias russian_trusted_root_ca \
        -file /tmp/russian_trusted_root_ca.crt -cacerts -storepass changeit \
 && keytool -importcert -noprompt -trustcacerts -alias russian_trusted_sub_ca \
        -file /tmp/russian_trusted_sub_ca.crt -cacerts -storepass changeit

# curl нужен для обращений к НСПД (nspd.gov.ru). Проверено 08.09.2026: с одного и того же
# адреса в одну секунду curl получает 200, а оба Java-клиента (HttpURLConnection и
# java.net.http.HttpClient) — 403. Заголовки при этом одинаковые, отличается только TLS-
# рукопожатие: WAF портала различает клиента по нему, а оба Java-клиента ходят через один
# и тот же JSSE. Те же корневые сертификаты кладём и в системное хранилище — иначе curl
# не проверит цепочку российского УЦ.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl ca-certificates \
 && cp /tmp/russian_trusted_root_ca.crt /tmp/russian_trusted_sub_ca.crt /usr/local/share/ca-certificates/ \
 && update-ca-certificates \
 && rm -rf /var/lib/apt/lists/* \
 && rm -f /tmp/russian_trusted_root_ca.crt /tmp/russian_trusted_sub_ca.crt

COPY --from=builder /app/target/procurement-bot-1.0-SNAPSHOT.jar ./app.jar
COPY src/main/resources/application.properties ./config/application.properties
COPY src/main/resources/logback.xml ./config/logback.xml
# Авто-подтягивание промежуточного CA при ротации (Russian Trusted Sub CA обновляется ~ежегодно):
# enableAIAcaIssuers включает фетч по AIA-ссылке из серта, allowedAIALocations снимает
# deny-all фильтр JDK 21 для CDP-хоста Нац.УЦ. Так валидация сохраняется, но смена Sub CA
# больше не ломает бота (Java сама скачает новый промежуточный, проверив его по стабильному Root).
ENV JAVA_OPTS="-Dfile.encoding=UTF-8 -Duser.timezone=Europe/Moscow -Dcom.sun.security.enableAIAcaIssuers=true -Dcom.sun.security.allowedAIALocations=http://nuc-cdp.digital.gov.ru"
CMD ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
