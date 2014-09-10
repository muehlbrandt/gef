/*******************************************************************************
 * Copyright (c) 2014 itemis AG and others.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Alexander Nyßen (itemis AG) - initial API and implementation
 *
 *******************************************************************************/
package org.eclipse.gef4.fx.tests;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;

import org.eclipse.gef4.common.reflect.ReflectionUtils;
import org.eclipse.gef4.fx.nodes.FXCurveConnection;
import org.eclipse.gef4.fx.nodes.IFXConnection;
import org.eclipse.gef4.geometry.planar.Point;
import org.junit.Test;

public class FXCurveConnectionTests {

	@Test
	public void test_PointConversions() {
		IFXConnection connection = new FXCurveConnection();
		Point startPoint = new Point(123, 456);
		Point wayPoint = new Point(789, 123);
		Point endPoint = new Point(456, 789);
		connection.setStartPoint(startPoint);
		connection.addWayPoint(0, wayPoint);
		connection.setEndPoint(endPoint);
		assertEquals(startPoint, connection.getStartPoint());
		assertEquals(wayPoint, connection.getWayPoint(0));
		assertEquals(endPoint, connection.getEndPoint());
	}

	@Test
	public void test_generateWayAnchorKey() throws IllegalArgumentException,
			IllegalAccessException {
		IFXConnection connection = new FXCurveConnection();
		Point startPoint = new Point(123, 456);
		Point wayPoint = new Point(789, 123);
		Point endPoint = new Point(456, 789);
		connection.setStartPoint(startPoint);
		connection.setEndPoint(endPoint);

		Field nextWayAnchorId = ReflectionUtils.getPrivateField(connection,
				"nextWayAnchorId");
		/*
		 * The first way anchor ID should be 0.
		 */
		assertEquals(0, nextWayAnchorId.get(connection));

		/*
		 * The ID increases with every added way point.
		 */
		connection.addWayPoint(0, wayPoint);
		connection.removeWayPoint(0);
		assertEquals(1, nextWayAnchorId.get(connection));

		/*
		 * Setting the ID to Integer.MAX_VALUE - 1 to verify that it is
		 * increased to Integer.MAX_VALUE.
		 */
		nextWayAnchorId.set(connection, Integer.MAX_VALUE - 1);
		connection.addWayPoint(0, wayPoint);
		connection.removeWayPoint(0);
		assertEquals(Integer.MAX_VALUE, nextWayAnchorId.get(connection));

		/*
		 * If the ID reaches Integer.MAX_VALUE, then all way points are
		 * re-assigned to IDs 0, 1, 2, ... N-1, N. As we did not have any way
		 * points here, the next ID will be 1 after adding a way point.
		 */
		connection.addWayPoint(0, wayPoint);
		connection.removeWayPoint(0);
		assertEquals(1, nextWayAnchorId.get(connection));
	}

}
