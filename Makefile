test:
	clojure -A:dev:test

confair.jar: src/confair/*.*
	clojure -A:jar

clean:
	rm -fr target confair.jar

deploy: confair.jar
	mvn deploy:deploy-file -Dfile=confair.jar -DrepositoryId=clojars -Durl=https://clojars.org/repo -DpomFile=pom.xml

.PHONY: test deploy clean
