FROM docker.elastic.co/elasticsearch/elasticsearch:8.18.8
COPY docker/infra/elasticsearch-analysis-ik-8.18.8.zip /tmp/ik.zip
RUN elasticsearch-plugin install --batch file:///tmp/ik.zip \
    && rm /tmp/ik.zip
