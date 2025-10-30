package org.matsim.run.analysis;

class HeadersKN{
	public static String modeSeq="modeSeq";
	public static String personId = "personId";
	public static String income = "income";
	public static String score = "score";
	public static String utlOfMoney = "utlOfMoney";
	public static String ttime = "ttime";
	public static String actSeq = "actSeq";
	public static String money = "money";
	public static String ascs = "ascs";

	private HeadersKN(){} // do not instantiate

	static String keyTwoOf( String str ) {
		return str + "_r";
	}
	static String deltaOf( String str ) {
		return "d_" + str;
	}
}
