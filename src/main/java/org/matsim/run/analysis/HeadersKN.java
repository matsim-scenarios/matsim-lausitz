package org.matsim.run.analysis;

class HeadersKN{
	public static final String WEIGHTED_MONEY = "w_money[u]";
	public static final String WEIGHTED_TTIME = "w_ttime[u]";
	public static final String MODE_SEQ ="modeSeq";
	public static final String PERSON_ID = "personId";
	public static final String INCOME = "income";
	public static final String SCORE = "SCORE[u]";
	public static final String BENEFIT = "wtp4score";
	public static final String UTL_OF_MONEY = "utlOfMoney";
	public static final String TTIME = "ttime[h]";
	public static final String ACT_SEQ = "actSeq";
	public static final String MONEY = "money";
	public static final String ASCS = "ascs";
	public static final String STUCK = "stuck";

	// do not instantiate
	private HeadersKN(){}

	static String keyTwoOf( String str ) {
		return str + "_r";
	}
	static String deltaOf( String str ) {
		return "d_" + str;
	}
}
