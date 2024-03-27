package org.matsim.run;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.matsim.api.core.v01.population.Population;
import org.matsim.application.MATSimApplication;
import org.matsim.core.api.experimental.events.EventsManager;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.controler.OutputDirectoryHierarchy;
import org.matsim.core.events.EventsUtils;
import org.matsim.core.population.PopulationUtils;
import org.matsim.simwrapper.SimWrapperConfigGroup;
import org.matsim.testcases.MatsimTestUtils;
import org.matsim.utils.eventsfilecomparison.ComparisonResult;
import org.matsim.utils.eventsfilecomparison.EventsFileFingerprintComparator;

import java.beans.EventHandler;
import java.io.File;

import static org.assertj.core.api.Assertions.assertThat;

public class RunIntegrationTest {

	@RegisterExtension
	public MatsimTestUtils utils = new MatsimTestUtils();

	@Test
	public void runScenario() {

		String output = utils.getOutputDirectory();
//
		Config config = ConfigUtils.loadConfig("input/v1.0/test_lausitz-v1.0-25pct.config.xml");

		config.global().setNumberOfThreads(2);
		config.qsim().setNumberOfThreads(2);
		config.controller().setLastIteration(1);
		config.routing().setRoutingRandomness(0);
		config.controller().setOverwriteFileSetting(OutputDirectoryHierarchy.OverwriteFileSetting.deleteDirectoryIfExists);
		config.controller().setOutputDirectory(output);

		ConfigUtils.addOrGetModule(config, SimWrapperConfigGroup.class).defaultDashboards = SimWrapperConfigGroup.Mode.disabled;
//
//
		MATSimApplication.execute(LausitzScenario.class, config, "run", "--1pct"
		);
////////
//////
//		EventsUtils.createEventsFingerprint(output+"lausitz-1pct.output_events.xml.gz",
//				utils.getInputDirectory()+"speedy_lausitz.fp.zst");


		EventsUtils.assertEqualEventsFingerprint(
				new File(output, "lausitz-1pct.output_events.xml.gz"),
				new File( utils.getInputDirectory(),"speedy_lausitz.fp.zst").toString()
		);

//		EventsManager manager = EventsUtils.createEventsManager();
//		RandomnessEventHandler testEvents = new RandomnessEventHandler();
//		manager.addHandler(testEvents);
//
//		EventsUtils.readEvents(manager,new File("./output_dijkstra_thread1/", "lausitz-1pct.output_events.xml.gz").toString());
//
//		manager.finishProcessing();
//
//		testEvents.writePath("./output_dijkstra_thread1/"+"freight_7532_path.txt");
//
//		Population pop1 = PopulationUtils.readPopulation("./output_speedy_thread1_norandom/"+"lausitz-1pct.output_plans.xml.gz");
//		Population pop2 = PopulationUtils.readPopulation("./output_speedy_thread2_norandom/"+"lausitz-1pct.output_plans.xml.gz");
//		boolean result = PopulationUtils.comparePopulations(pop1,pop2);
//
//		System.out.println(result);

//		var Result = EventsFileFingerprintComparator.createFingerprintHandler(
//				new File("./output_speedy_thread1_norandom/", "lausitz-1pct.output_events.xml.gz").toString(),
//				new File("./output_speedy_thread4_norandom/", "speedy_lausitz.fp.zst").toString());
//
//		System.out.println(Result.getComparisonMessage());
//
//		System.out.println(Result.getComparisonResult());

	}
}
