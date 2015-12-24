package com.dg;

import java.sql.*;

/**
 * @author doc
 */
public class Database {
	private final String path;
	private Connection connection;

	private boolean lastSuccess = false;

	public Database(String path){
		this.path = path;
	}

	public void connect() throws SQLException {
		connection = DriverManager.getConnection("jdbc:hsqldb:" + path, "sa", "");
	}

	public Object[] fetch(ResultSet rs) throws SQLException{
		ResultSetMetaData meta = rs.getMetaData();
		int colCount = meta.getColumnCount();
		Object[] row = new Object[colCount];
		for(int i = 0; i < colCount; i++)
			row[i] = rs.getObject(meta.getColumnName(i + 1));
		return row;
	}

	public Statement query(String sql) throws SQLException{
		Statement statement = connection.createStatement();
		lastSuccess = statement.execute(sql);
		return statement;
	}

	public boolean success(){
		return lastSuccess;
	}

	public void disconnect(){
		try {
			connection.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	public Connection getConnection(){
		return connection;
	}
}