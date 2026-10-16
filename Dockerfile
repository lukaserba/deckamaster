# Runtime image for the Deckamaster server.
# Expects a build context prepared by CI (see .github/workflows/forge-sim.yml):
#   bundle/forge.jar, bundle/deckamaster-server.jar, bundle/res/, bundle/web/, bundle/decks/
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY bundle/ /app/
EXPOSE 8080
ENV JAVA_OPTS="-Xmx2g"
CMD ["sh", "-c", "exec java $JAVA_OPTS -cp forge.jar:deckamaster-server.jar cc.deckamaster.server.Main 8080 web decks"]
