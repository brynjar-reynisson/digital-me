package com.breynisson.router;

import com.breynisson.router.jdbc.PostgresTestSupport;
import com.breynisson.router.jdbc.TextEntryDao;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;

import org.apache.camel.test.spring.junit5.CamelSpringBootTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@CamelSpringBootTest
public class SpringBootApplicationTest {

	private static final String FIXTURE_SOURCE_URL = "claude://sample-project/11111111-1111-1111-1111-111111111111";

	@TempDir
	static Path dataDir;

	private static String schema;

	@DynamicPropertySource
	static void overrideProperties(DynamicPropertyRegistry registry) throws URISyntaxException {
		// ClaudeSessionIndexer's @Scheduled job runs as part of this full context. Left pointed at
		// the real ~/.claude/projects, it would scan and re-embed the user's actual session history
		// (hundreds of files) on every test run instead of a fixed, fast, deterministic dataset, and
		// would write actual transcripts into a shared, never-cleaned-up directory on disk.
		registry.add("data.dir", () -> dataDir.toString());
		Path fixtureDir = Paths.get(SpringBootApplicationTest.class.getClassLoader()
				.getResource("claude-projects-fixture").toURI());
		registry.add("claude.projects.dir", fixtureDir::toString);

		// Isolate this full-context test's Postgres schema from real dev data and from every
		// other test class, the same way data.dir isolates its file-based state above.
		schema = "springbootapplicationtest_" + java.util.UUID.randomUUID().toString().replace("-", "");
		registry.add("postgres.schema", () -> schema);
	}

	@AfterAll
	static void tearDownDatabase() {
		PostgresTestSupport.dropSchema(schema);
	}

	@Autowired
	private CamelContext camelContext;

	@Autowired
	private ProducerTemplate producerTemplate;

	@Test
	public void test() throws Exception {
		assertTrue(true);
	}

	@Test
	void indexesFixtureClaudeSessionOnStartup() throws Exception {
		long deadline = System.currentTimeMillis() + 20_000;
		while (TextEntryDao.findByName(FIXTURE_SOURCE_URL).isEmpty()) {
			if (System.currentTimeMillis() > deadline) {
				org.junit.jupiter.api.Assertions.fail(
						"ClaudeSessionIndexer did not index the fixture session within 20s");
			}
			Thread.sleep(200);
		}
		assertTrue(true);
	}
}
