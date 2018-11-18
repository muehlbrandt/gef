/*******************************************************************************
 * Copyright (c) 2016, 2018 itemis AG and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Alexander Nyßen (itemis AG) - initial API and implementation
 *     Tamas Miklossy  (itemis AG) - implement additional validation rules
 *******************************************************************************/
package org.eclipse.gef.dot.internal.language.validation;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.eclipse.emf.common.util.EList;
import org.eclipse.gef.dot.internal.DotAttributes.Context;
import org.eclipse.gef.dot.internal.language.style.ClusterStyle;
import org.eclipse.gef.dot.internal.language.style.EdgeStyle;
import org.eclipse.gef.dot.internal.language.style.NodeStyle;
import org.eclipse.gef.dot.internal.language.style.Style;
import org.eclipse.gef.dot.internal.language.style.StyleItem;
import org.eclipse.gef.dot.internal.language.style.StylePackage;
import org.eclipse.xtext.nodemodel.INode;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.validation.Check;

/**
 * This class contains custom validation rules.
 *
 * See
 * https://www.eclipse.org/Xtext/documentation/303_runtime_concepts.html#validation
 */
public class DotStyleJavaValidator extends
		org.eclipse.gef.dot.internal.language.validation.AbstractDotStyleJavaValidator {

	/**
	 * Validates that the used {@link StyleItem}s are applicable in the
	 * respective {@link Context}.
	 *
	 * @param styleItem
	 *            The {@link StyleItem} to check.
	 */
	@Check
	public void checkStyleItemConformsToContext(StyleItem styleItem) {
		// The use of setlinewidth is deprecated, but still valid
		String name = styleItem.getName();
		if (name.equals("setlinewidth")) {
			return;
		}

		Context attributeContext = getAttributeContext();
		if (Context.NODE.equals(attributeContext)) {
			for (Object validValue : NodeStyle.values()) {
				if (validValue.toString().equals(name)) {
					return;
				}
			}
			// check each style item with the corresponding parser
			reportRangeBaseError(
					"Value should be one of "
							+ getFormattedValues(NodeStyle.values()) + ".",
					styleItem, attributeContext);
		} else if (Context.EDGE.equals(attributeContext)) {
			for (Object validValue : EdgeStyle.values()) {
				if (validValue.toString().equals(name)) {
					return;
				}
			}
			// check each style item with the corresponding parser
			reportRangeBaseError(
					"Value should be one of "
							+ getFormattedValues(EdgeStyle.values()) + ".",
					styleItem, attributeContext);
		} else if (Context.GRAPH.equals(attributeContext)
				|| Context.CLUSTER.equals(attributeContext)) {
			for (Object validValue : ClusterStyle.values()) {
				if (validValue.toString().equals(name)) {
					return;
				}
			}
			// check each style item with the corresponding parser
			reportRangeBaseError(
					"Value should be one of "
							+ getFormattedValues(ClusterStyle.values()) + ".",
					styleItem, attributeContext);
		}
		// do nothing if the DOT attribute context cannot be determined. In such
		// cases this validation rule should have no effect.
	}

	/**
	 * Validates that the used {@link StyleItem}s are not deprecated. Generates
	 * warnings in case of the usage of deprecated style items.
	 *
	 * @param styleItem
	 *            The {@link StyleItem} to check.
	 */
	@Check
	public void checkDeprecatedStyleItem(StyleItem styleItem) {
		if (styleItem.getName().equals("setlinewidth")) {
			reportRangeBasedWarning(
					"The usage of setlinewidth is deprecated, use the penwidth attribute instead.",
					styleItem);
		}
	}

	/**
	 * Validates that the used {@link Style} does not contains duplicates.
	 * Generates warnings in case of the usage of duplicated style items.
	 *
	 * @param style
	 *            The {@link Style} to check.
	 */
	@Check
	public void checkDuplicatedStyleItem(Style style) {
		Set<String> definedStyles = new HashSet<>();

		EList<StyleItem> styleItems = style.getStyleItems();
		// iterate backwards as the last styleItem value will be used
		for (int i = styleItems.size() - 1; i >= 0; i--) {
			StyleItem styleItem = styleItems.get(i);
			String name = styleItem.getName();
			if (!definedStyles.add(name)) {
				reportRangeBasedWarning(
						"The style value '" + name + "' is duplicated.",
						styleItem);
			}
		}
	}

	private void reportRangeBasedWarning(String message, StyleItem styleItem) {

		List<INode> nodes = NodeModelUtils.findNodesForFeature(styleItem,
				StylePackage.Literals.STYLE_ITEM__NAME);

		if (nodes.size() != 1) {
			throw new IllegalStateException(
					"Exact 1 node is expected for the feature, but got "
							+ nodes.size() + " node(s).");
		}

		INode node = nodes.get(0);
		int offset = node.getTotalOffset();
		int length = node.getLength();

		String code = null;
		// the issueData will be evaluated by the quickfixes
		String[] issueData = { styleItem.getName() };
		getMessageAcceptor().acceptWarning(message, styleItem, offset, length,
				code, issueData);
	}

	private void reportRangeBaseError(String message, StyleItem styleItem,
			Context attributeContext) {

		List<INode> nodes = NodeModelUtils.findNodesForFeature(styleItem,
				StylePackage.Literals.STYLE_ITEM__NAME);

		if (nodes.size() != 1) {
			throw new IllegalStateException(
					"Exact 1 node is expected for the feature, but got "
							+ nodes.size() + " node(s).");
		}

		INode node = nodes.get(0);
		int offset = node.getTotalOffset();
		int length = node.getLength();

		String code = null;
		// the issueData will be evaluated by the quickfixes
		String[] issueData = { styleItem.getName(),
				attributeContext.toString() };
		getMessageAcceptor().acceptError(message, styleItem, offset, length,
				code, issueData);
	}

	private Context getAttributeContext() {
		// XXX: This context information is provided by the EObjectValidator
		Context attributeContext = (Context) getContext()
				.get(Context.class.getName());
		return attributeContext;
	}

	private String getFormattedValues(Object[] values) {
		StringBuilder sb = new StringBuilder();
		for (Object value : new TreeSet<>(Arrays.asList(values))) {
			if (sb.length() > 0) {
				sb.append(", ");
			}
			sb.append("'" + value + "'");
		}
		return sb.toString();
	}
}