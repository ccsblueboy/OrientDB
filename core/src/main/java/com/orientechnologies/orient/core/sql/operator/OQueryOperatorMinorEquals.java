/*
 * Copyright 1999-2010 Luca Garulli (l.garulli--at--orientechnologies.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.orientechnologies.orient.core.sql.operator;

import com.orientechnologies.common.profiler.OProfiler;
import com.orientechnologies.orient.core.command.OCommandContext;
import com.orientechnologies.orient.core.db.record.OIdentifiable;
import com.orientechnologies.orient.core.id.ORID;
import com.orientechnologies.orient.core.index.*;
import com.orientechnologies.orient.core.metadata.schema.OType;
import com.orientechnologies.orient.core.record.impl.ODocumentHelper;
import com.orientechnologies.orient.core.sql.filter.OSQLFilterCondition;
import com.orientechnologies.orient.core.sql.filter.OSQLFilterItemField;
import com.orientechnologies.orient.core.sql.filter.OSQLFilterItemParameter;

import java.util.Collection;
import java.util.List;

/**
 * MINOR EQUALS operator.
 * 
 * @author Luca Garulli
 * 
 */
public class OQueryOperatorMinorEquals extends OQueryOperatorEqualityNotNulls {

	public OQueryOperatorMinorEquals() {
		super("<=", 5, false);
	}

	@Override
	@SuppressWarnings("unchecked")
	protected boolean evaluateExpression(final OIdentifiable iRecord, final OSQLFilterCondition iCondition, final Object iLeft,
			final Object iRight, OCommandContext iContext) {
		final Object right = OType.convert(iRight, iLeft.getClass());
		if (right == null)
			return false;
		return ((Comparable<Object>) iLeft).compareTo(right) <= 0;
	}

	@Override
	public OIndexReuseType getIndexReuseType(final Object iLeft, final Object iRight) {
		if (iRight == null || iLeft == null)
			return OIndexReuseType.NO_INDEX;
		return OIndexReuseType.INDEX_METHOD;
	}

	@Override
	public Collection<OIdentifiable> executeIndexQuery(OIndex<?> index, List<Object> keyParams, int fetchLimit) {
		final OIndexDefinition indexDefinition = index.getDefinition();

		final OIndexInternal internalIndex = index.getInternal();
		if(!internalIndex.canBeUsedInEqualityOperators())
			return null;

		final Collection<OIdentifiable> result;
		if(indexDefinition.getParamCount() == 1) {
			final Object key;
			if (indexDefinition instanceof OIndexDefinitionMultiValue)
				key = ((OIndexDefinitionMultiValue) indexDefinition).createSingleValue(keyParams.get(0));
			else
				key = indexDefinition.createValue(keyParams);

			if (key == null)
				return null;

			if (fetchLimit > -1)
				result = index.getValuesMinor(key, true, fetchLimit);
			else
				result = index.getValuesMinor(key, true);
		} else {
			// if we have situation like "field1 = 1 AND field2 <= 2"
			// then we fetch collection which left included boundary is the smallest composite key in the
			// index that contains key with value field1=1 and which right not included boundary
			// is the biggest composite key in the index that contains key with value field1=1 and field2=2.

			final Object keyOne = indexDefinition.createValue(keyParams.subList(0, keyParams.size() - 1));

			if (keyOne == null)
				return null;

			final Object keyTwo = indexDefinition.createValue(keyParams);

			if (keyTwo == null)
				return null;

			if (fetchLimit > -1)
				result = index.getValuesBetween(keyOne, true, keyTwo, true, fetchLimit);
			else
				result = index.getValuesBetween(keyOne, true, keyTwo, true);

			if (OProfiler.getInstance().isRecording()) {
				OProfiler.getInstance().updateCounter("Query.compositeIndexUsage", 1);
				OProfiler.getInstance().updateCounter("Query.compositeIndexUsage." + indexDefinition.getParamCount(), 1);
			}
		}
		return result;
	}

	@Override
  public ORID getBeginRidRange(Object iLeft, Object iRight) {
    return null;
  }

  @Override
  public ORID getEndRidRange(final Object iLeft,final Object iRight) {
    if (iLeft instanceof OSQLFilterItemField &&
            ODocumentHelper.ATTRIBUTE_RID.equals(((OSQLFilterItemField) iLeft).getRoot()))
      if (iRight instanceof ORID)
        return (ORID) iRight;
      else {
        if (iRight instanceof OSQLFilterItemParameter &&
                ((OSQLFilterItemParameter) iRight).getValue(null, null) instanceof ORID)
          return (ORID) ((OSQLFilterItemParameter) iRight).getValue(null, null);
      }

    return null;
  }
}
