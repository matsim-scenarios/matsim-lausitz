package org.matsim.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.application.MATSimApplication;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.utils.eventsfilecomparison.ComparisonResult;

import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

public class RunIntegrationTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public void runScenario() {

		String output = utils.getOutputDirectory();

		Config config = ConfigUtils.loadConfig("input/v1.0/lausitz-v1.0-25pct.config.xml");

		config.global().setNumberOfThreads(1);
		config.qsim().setNumberOfThreads(1);
		config.controller().setLastIteration(1);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setOutputDirectory(output);

		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;


		MATSimApplication.execute(LausitzScenario.class, config, "run", "--1pct"
		);
//////
////
//		EventsUtils.createEventsFingerprint(utils.getOutputDirectory()+"lausitz-1pct.output_events.xml.gz",
//				utils.getInputDirectory()+"lausitz.fp.zst");

		EventsUtils.assertEqualEventsFingerprint(
				new File("test/output/org/matsim/run/RunIntegrationTest/runScenario/", "lausitz-1pct.output_events.xml.gz"),
				new File(utils.getInputDirectory(), "lausitz.fp.zst").toString()
		);


	}
}
