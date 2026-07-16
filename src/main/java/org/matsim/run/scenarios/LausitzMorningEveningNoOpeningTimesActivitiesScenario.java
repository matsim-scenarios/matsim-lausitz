package org.matsim.run.scenarios;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.matsim.api.core.v01.Scenario;
import org.matsim.api.core.v01.population.Activity;
import org.matsim.api.core.v01.population.Person;
import org.matsim.api.core.v01.population.Plan;
import org.matsim.contrib.vsp.scenario.SnzActivities;
import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ScoringConfigGroup;
import org.matsim.core.controler.Controler;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Lausitz scenario including separate act types and acts for morning and evening to switch of wrap-around scoring.
 * Further, opening times are removed for every act type.
 * All necessary configs will be made in this class.
 */
public class LausitzMorningEveningNoOpeningTimesActivitiesScenario extends LausitzScenario {
	private static final Logger log = LogManager.getLogger(LausitzMorningEveningNoOpeningTimesActivitiesScenario.class);

	public LausitzMorningEveningNoOpeningTimesActivitiesScenario(@Nullable Config config) {
		super(config);
	}

	public LausitzMorningEveningNoOpeningTimesActivitiesScenario(@Nullable String args) {
		super(args);
	}

	public LausitzMorningEveningNoOpeningTimesActivitiesScenario() {
		super(String.format("input/v%s/lausitz-v%s-10pct.config.xml", LausitzScenario.VERSION, LausitzScenario.VERSION));
	}

	@Override
	protected void addScoringParams(Config config) {
		// yyyy need to find a way to remove the existing scoring params; then this can be programmed without inheritance
//		add scoring params for split act types for _morning and _evening. See method prepareScenario.
		addMorningEveningScoringParams(config);
	}

	@Nullable
	@Override
	public Config prepareConfig(Config config) {
		//		apply all config changes from base scenario class
		super.prepareConfig(config);

		config.timeAllocationMutator().setLatestActivityEndTime(String.valueOf(config.qsim().getEndTime().seconds()));
		config.timeAllocationMutator().setMutateAroundInitialEndTimeOnly(false);
		config.timeAllocationMutator().setAffectingDuration(false);

		return config;
	}

	@Override
	public void prepareScenario(Scenario scenario) {
		//		apply all scenario changes from base scenario class
		super.prepareScenario(scenario);

		changeWrapAroundActsIntoMorningAndEveningActs(scenario);
	}

	@Override
	public void prepareControler(Controler controler) {
		//		apply all controller changes from base scenario class
		super.prepareControler(controler);
	}

	/**
	 * Method copied from org.matsim.contrib.vsp.scenario.Activities as we did not want to use a more modern matsim version,
	 * to keep comparability to matsim-lausitz v2.0.
	 * Disable wrap-around scoring of first and last act of the day by setting them to different subtypes "_morning" and "_evening".
	 */
	protected static void changeWrapAroundActsIntoMorningAndEveningActs(Scenario scenario) {
		Set<String> firstActTypes = new HashSet<>();
		Set<String> lastActTypes = new HashSet<>();

		for ( Person p : scenario.getPopulation().getPersons().values()) {
//			ignore freight / commercial traffic agents and stay home agents
			if (!p.getAttributes().getAttribute("subpopulation").equals("person") ||
				p.getSelectedPlan().getPlanElements().size() == 1) {
				continue;
			}

			for ( Plan plan : p.getPlans()) {
				Activity first = (Activity) plan.getPlanElements().getFirst();
				Activity last = (Activity) plan.getPlanElements().getLast();

				String[] splitFirst = first.getType().split("_");
				String typeFirst = String.join("_", Arrays.copyOfRange(splitFirst, 0, splitFirst.length - 1 ) );
				int orginalTimeBinFirst = Integer.parseInt(splitFirst[splitFirst.length - 1]);
				firstActTypes.add(typeFirst);

				String[] splitLast = last.getType().split("_");
				String typeLast = String.join("_", Arrays.copyOfRange(splitLast, 0, splitLast.length - 1));
				int orginalTimeBinLast = Integer.parseInt(splitLast[splitLast.length - 1]);
				lastActTypes.add(typeLast);

				if (!typeFirst.equals(typeLast)) {
//					if first and last act do not have the same type, we will not change anything.
//					this is the pragmatic version. There are last acts with without startTime, endTime or maxDuration.
//					this needs to be repaired upstream (in the makefile process). -sm0226
					continue;
				}

				Double durationFirst = null;
				if (first.getEndTime().isDefined()) {
//					use act end time if defined
					durationFirst = first.getEndTime().seconds();
				}

				if (durationFirst == null && first.getMaximumDuration().isDefined()) {
					durationFirst = first.getMaximumDuration().seconds();
				}

				if (durationFirst == null) {
					log.fatal("Neither duration nor end time is defined for activity {} of agent {}. This should not happen, aborting!", first, p.getId() );
					throw new IllegalStateException("");
				}

				int durationBinFirst = getDurationBin(durationFirst);

				first.setType(String.format("%s_%d", createMorningActivityType(typeFirst), durationBinFirst));

				//			act types of first and last act the same
				if (orginalTimeBinFirst != orginalTimeBinLast) {
					log.fatal("typical duration of first and last activity of person {} with the same act type {} are not the same. This should not happen, aborting!", p.getId(), typeLast );
					throw new IllegalStateException("");
				}
				double durationLast = orginalTimeBinLast - durationFirst;

				last.setType(String.format("%s_%d", createEveningActivityType(typeLast), getDurationBin(durationLast)));
				last.setMaximumDuration(durationLast);
				last.setEndTimeUndefined();
				last.setStartTimeUndefined();
			}
		}
		log.info("Activity types of first activity in plans: {}", firstActTypes );
		log.info("Activity types of last activity in plans: {}", lastActTypes );
	}
	private static int getDurationBin(Double duration) {
		final int maxCategories = 86400 / 600;

		int durationCategoryNr = (int) Math.round(duration / 600);

		if (durationCategoryNr <= 0) {
			durationCategoryNr = 1;
		}

		if (durationCategoryNr >= maxCategories) {
			durationCategoryNr = maxCategories;
		}
		return durationCategoryNr * 600;
	}

	/**
	 * Method copied from SnzActivities as we did not want to use a more modern matsim version,
	 * 	 * to keep comparability to matsim-lausitz v2.0.
	 * Add activity params for the scenario config.
	 */
	protected static void addMorningEveningScoringParams(Config config) {
		// doing the activities without value.apply means it does not apply the opening times

		for (SnzActivities value : SnzActivities.values()) {
			for (long ii = 600; ii <= 97200; ii += 600) {
				config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(value.name() + "_" + ii).setTypicalDuration(ii));
				config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(createMorningActivityType( value.name())+"_"+ii).setTypicalDuration(ii));
				config.scoring().addActivityParams(new ScoringConfigGroup.ActivityParams(createEveningActivityType( value.name())+"_"+ii).setTypicalDuration(ii));
			}
		}
	}

	private static String createMorningActivityType(String baseActType) {
		return baseActType + "_morning";
	}
	private static String createEveningActivityType(String baseActType) {
		return baseActType + "_evening";
	}
}
