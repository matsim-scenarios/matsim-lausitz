package org.matsim.run.scenarios;

import com.google.common.collect.Lists;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.TransportMode;
import org.matsim.api.core.v01.population.*;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ReplanningConfigGroup;
import org.matsim.core.controler.Controler;
import org.matsim.core.population.algorithms.TripsToLegsAlgorithm;
import org.matsim.core.router.RoutingModeMainModeIdentifier;
import picocli.CommandLine;

import javax.annotation.Nullable;
import java.util.Collection;

/**
 * Lausitz scenario including the simulation of 1 single transport mode.
 * All legs are changed to --transport-mode and mode choice is switched off.
 * All necessary configs will be made in this class.
 */
public class LausitzSingleModeScenario extends LausitzScenario {
	Logger log = LogManager.getLogger(LausitzSingleModeScenario.class);

	private final LausitzScenario baseScenario = new LausitzScenario(sample, emissions);

	@CommandLine.Option(names = "--transport-mode", description = "Transport mode to which all legs should be changed.", defaultValue = TransportMode.car)
	private String mode;

	public LausitzSingleModeScenario(@Nullable Config config) {
		super(config);
	}

	public LausitzSingleModeScenario(@Nullable String args) {
		super(args);
	}

	public LausitzSingleModeScenario() {
		super(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
	}

	@Nullable
	@Override
	public Config prepareConfig(Config config) {
		//		apply all config changes from base scenario class
		baseScenario.prepareConfig(config);

		config.subtourModeChoice().setModes(new String[0]);

//		remove smc strategy, only one mode for this scenario
		Collection<ReplanningConfigGroup.StrategySettings> settings = config.replanning().getStrategySettings();
		config.replanning().clearStrategySettings();

		settings.stream()
			.filter(setting -> !setting.getStrategyName().equals("SubtourModeChoice"))
			.forEach(setting -> config.replanning().addStrategySettings(setting));

		return config;
	}

	@Override
	public void prepareScenario(Scenario scenario) {
		//		apply all scenario changes from base scenario class
		baseScenario.prepareScenario(scenario);

//		TODO: replace code segment that selects, cleans routes and converts trips 2 legs with call of CleanPopulation PR3496
		TripsToLegsAlgorithm trips2Legs = new TripsToLegsAlgorithm(new RoutingModeMainModeIdentifier());

		for (Person person : scenario.getPopulation().getPersons().values()) {
			Plan selected = person.getSelectedPlan();
			for (Plan plan : Lists.newArrayList(person.getPlans())) {
				if (plan != selected)
					person.removePlan(plan);
			}

			for (Plan plan : person.getPlans()) {
				trips2Legs.run(plan);

				for (PlanElement el : plan.getPlanElements()) {
					if (el instanceof Leg leg) {
						leg.setRoute(null);
						leg.setMode(mode);
					}
				}
			}
		}
		log.info("For all agents: Unselected plans have been removed. Trips were converted to legs. Routes have been removed." +
			" For every leg, the mode was changed to {}", mode);
	}

	@Override
	public void prepareControler(Controler controler) {
		//		apply all controller changes from base scenario class
		baseScenario.prepareControler(controler);
	}
}
