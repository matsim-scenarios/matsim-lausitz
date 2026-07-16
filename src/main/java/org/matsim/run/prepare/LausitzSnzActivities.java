package org.matsim.run.prepare;

import org.matsim.core.config.Config;
import org.matsim.core.config.groups.ScoringConfigGroup.ActivityParams;

/**
 * Defines available activities and open- and closing times in Snz scenarios at vsp.
 */
public enum LausitzSnzActivities {

	home,
	other,
	visit,
	accomp_children,
	accomp_other,

	educ_kiga(7, 17),
//	5-18 in base ctd
	educ_primary(7, 16),
//	5-18 in base ctd
	educ_secondary(7, 17),
//	5-21 in base ctd
	educ_tertiary(7, 22),
//	5-23 in base ctd
	educ_higher(7, 22),
//	4-23 in base ctd
	educ_other(7, 22),

	work(5, 22),
	business(5, 22),
	errands(5, 22),

	leisure(5, 27),
//	we do not have restaurant in the lausitz model
//	restaurant(8, 27),
	shop_daily(6, 20),
	shop_other(6, 20);

	/**
	 * Start time of an activity in hours, can be -1 if not defined.
	 */
	private final double start;

	/**
	 * End time of an activity in hours, can be -1 if not defined.
	 */
	private final double end;

	LausitzSnzActivities(double start, double end) {
		this.start = start;
		this.end = end;
	}

	LausitzSnzActivities() {
		this.start = -1;
		this.end = -1;
	}


	/**
	 * Apply start and end time to params.
	 */
	public ActivityParams apply( ActivityParams params ) {
		if (start >= 0)
			params = params.setOpeningTime(start * 3600.);
		if (end >= 0)
			params = params.setClosingTime(end * 3600.);

		return params;
	}

	/**
	 * Add activity params for the scenario config.
	 */
	public static void addScoringParams(Config config) {

		for (LausitzSnzActivities value : LausitzSnzActivities.values()) {
			for (long ii = 600; ii <= 97200; ii += 600) {
				config.scoring().addActivityParams(value.apply(new ActivityParams(value.name() + "_" + ii).setTypicalDuration(ii)));
			}
		}

		config.scoring().addActivityParams(new ActivityParams("other").setTypicalDuration(600 * 3));

		config.scoring().addActivityParams(new ActivityParams("freight_start").setTypicalDuration(60 * 15));
		config.scoring().addActivityParams(new ActivityParams("freight_end").setTypicalDuration(60 * 15));

	}
}
