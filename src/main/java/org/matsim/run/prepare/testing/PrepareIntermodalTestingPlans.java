package org.matsim.run.prepare.testing;

import org.matsim.api.core.v01.Id;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.application.MATSimAppCommand;
import org.matsim.core.config.Config;
import org.matsim.core.config.ConfigUtils;
import org.matsim.core.scenario.ScenarioUtils;
import picocli.CommandLine;

import java.util.Random;

public class PrepareIntermodalTestingPlans implements MATSimAppCommand {
	@CommandLine.Option(names = "--output", description = "Path to output population", required = true)
	private String output;

	public static void main(String[] args) {
		new PrepareIntermodalTestingPlans().execute(args);
	}

	@Override
	public Integer call() throws Exception {
		Random random = new Random(1);
		Config config = ConfigUtils.createConfig();
		Scenario scenario = ScenarioUtils.loadScenario(config);
		Population population = scenario.getPopulation();
		PopulationFactory populationFactory = population.getFactory();

		for (int i = 0; i < 500; i++) {
			Person person = populationFactory.createPerson(Id.createPersonId("dummy_person_" + i));
			Plan plan = populationFactory.createPlan();
			// a random location in the Hoyerswerda town center
			Activity fromAct = populationFactory.createActivityFromLinkId("dummy", Id.createLinkId("-203216578#2"));
			// a random time between 6:00-9:00
			fromAct.setEndTime(21600 + random.nextInt(10800));
			// set the link to PT, such that agent could find a potential intermodal trip
			Leg leg = populationFactory.createLeg(TransportMode.pt);
			// a location close to Cottbus Hbf
			Activity toAct = populationFactory.createActivityFromLinkId("dummy", Id.createLinkId("863043626#0"));

			plan.addActivity(fromAct);
			plan.addLeg(leg);
			plan.addActivity(toAct);

			person.addPlan(plan);
			population.addPerson(person);
		}

		new PopulationWriter(population).write(output);

		return 0;
	}
}
