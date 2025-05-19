package org.matsim.run;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.run.drtpostsimulation.RunDrtPostSimulation;
import org.matsim.run.scenarios.LausitzDrtScenario;
import org.matsim.run.scenarios.LausitzScenario;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;

import java.nio.file.Path;

public class DrtPostSimulationTest {
	@RegisterExtension
	private final MatsimTestUtils utils = new MatsimTestUtils();

	@TempDir
	private Path p;

	private String inputPath;

	@BeforeEach
	void setUp() {
		// Initialize inputPath after p is injected
		inputPath = p.resolve("test-population.xml.gz").toString();
	}

	@Test
	void runDrtPostSimulationTest() {
//		first step: run sim to create drt output
		Config config = ConfigUtils.loadConfig(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;

		new RunIntegrationTest().createDrtTestPopulation(inputPath);

		assert MATSimApplication.execute(LausitzDrtScenario.class, config,
			"--1pct",
			"--iterations", "0",
			"--config:plans.inputPlansFile", inputPath,
			"--output", utils.getOutputDirectory(),
			"--config:controller.overwriteFiles=deleteDirectoryIfExists",
			"--config:global.numberOfThreads", "2",
			"--config:qsim.numberOfThreads", "2",
			"--emissions", "DO_NOT_PERFORM_EMISSIONS_ANALYSIS"
		)
			== 0 : "Must return non error code";

//		second step: run drt post sim with output of first step.
		new RunDrtPostSimulation().execute("--config", "replace-by-meaningful-config.xml",
			"--main-sim-output", utils.getOutputDirectory(),
			"--fleet-sizing", "1 2 1",
			"--capacity", "1",
//			TODO: make the path below relative to config path
			"--shp", "./drt-area/hoyerswerda-ruhland_Bhf-utm32N.shp"



			);


	}
}
