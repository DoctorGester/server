package com.dg;

/**
 * @author doc
 */
public class RewriteRule {
	private final String from;
	private final String to;
	private String option = "";

	public RewriteRule(String from, String to) {
		this.from = from;
		this.to = to;
	}

	public void setOption(String option) {
		this.option = option;
	}

	public String getOption() {
		return option;
	}

	public String getFrom() {
		return from;
	}

	public String getTo() {
		return to;
	}
}
