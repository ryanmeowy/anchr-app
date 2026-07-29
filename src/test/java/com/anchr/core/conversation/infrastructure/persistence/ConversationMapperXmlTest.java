package com.anchr.core.conversation.infrastructure.persistence;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationMapperXmlTest {

    @Test
    void shouldParseSessionKeysetAndAtomicMetadataStatements() throws Exception {
        Configuration configuration = new Configuration();
        configuration.addMapper(ConversationMapper.class);
        String resource = "mapper/conversation/ConversationMapper.xml";

        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }

        String namespace = ConversationMapper.class.getName() + ".";
        assertThat(configuration.hasStatement(namespace + "insertSession")).isTrue();
        assertThat(configuration.hasStatement(namespace + "findSessionPage")).isTrue();
        assertThat(configuration.hasStatement(namespace + "renameSession")).isTrue();
        assertThat(configuration.hasStatement(namespace + "touchSessionIfNewer")).isTrue();
        assertThat(configuration.hasStatement(namespace + "updateAutoTitleIfUnchanged")).isTrue();

        for (String statementId : new String[]{
                "renameSession", "touchSessionIfNewer", "updateAutoTitleIfUnchanged"}) {
            MappedStatement statement = configuration.getMappedStatement(namespace + statementId);
            assertThat(statement.getBoundSql(null).getSql())
                    .containsIgnoringCase("timestampadd(microsecond, 1000, updated_at)");
        }
    }
}
