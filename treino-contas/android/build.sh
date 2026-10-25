#!/usr/bin/env bash
# Gera treino-contas/TreinoDeContas.apk sem Android SDK.
# Precisa de: java, javac, python3, mvn (baixa smali e apksig do Maven Central).
set -euo pipefail
cd "$(dirname "$0")"
OUT=build
rm -rf "$OUT" && mkdir -p "$OUT/assets" "$OUT/lib"

# Ferramentas: smali (gera o classes.dex) e apksig (assina o APK)
cat > "$OUT/pom.xml" <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>x</groupId><artifactId>tools</artifactId><version>1</version><dependencies>
<dependency><groupId>org.smali</groupId><artifactId>smali</artifactId><version>2.5.2</version></dependency>
<dependency><groupId>com.android.tools.build</groupId><artifactId>apksig</artifactId><version>2.3.0</version></dependency>
</dependencies></project>
EOF
mvn -q -f "$OUT/pom.xml" dependency:copy-dependencies -DoutputDirectory=lib
CP="$OUT/lib/*"

# Assets: o mesmo index.html do app (funciona sem internet)
cp ../index.html "$OUT/assets/index.html"

java -cp "$CP" org.jf.smali.Main assemble -o "$OUT/classes.dex" MainActivity.smali
python3 build_apk.py "$OUT/classes.dex" "$OUT/assets" ../icon-192.png "$OUT/unsigned.apk"

# Chave de assinatura: mantenha o mesmo arquivo para poder atualizar o app depois
[ -f contas.p12 ] || keytool -genkeypair -keystore contas.p12 -storetype PKCS12 \
  -storepass contas123 -keypass contas123 -alias contas -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=Treino de Contas"
javac -cp "$CP" -d "$OUT" Sign.java
java --add-exports java.base/sun.security.x509=ALL-UNNAMED -cp "$OUT:$CP" Sign contas.p12 contas123 contas "$OUT/unsigned.apk" ../TreinoDeContas.apk
echo "Pronto: treino-contas/TreinoDeContas.apk"
