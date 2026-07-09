package org.matsim.run;

import org.matsim.application.MATSimApplication;
import org.matsim.run.scenarios.LausitzBikeTeleportedScenario;

/**
 * Run the Lausitz scenario with bike as teleported mode.
 */
public final class RunLausitzBikeTeleportedScenario {

	private RunLausitzBikeTeleportedScenario() {
	}

	public static void main(String[] args) {
		MATSimApplication.execute(LausitzBikeTeleportedScenario.class, args);
	}

}
