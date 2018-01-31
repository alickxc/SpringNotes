/*
 * Copyright 2002-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.StringValueResolver;

/**
 * Simple implementation of the {@link AliasRegistry} interface.
 * Serves as base class for
 * {@link org.springframework.beans.factory.support.BeanDefinitionRegistry}
 * implementations.
 *
 * @author Juergen Hoeller
 * @since 2.5.2
 */

//BeanFactory对别名的支持
public class SimpleAliasRegistry implements AliasRegistry {

	/** Map from alias to canonical name */
	//用于存储Bean与别名的对应关系 别名-Bean名称 PS 可能是为了查起来方便
	private final Map<String, String> aliasMap = new ConcurrentHashMap<String, String>(16);

	//注册别名
	public void registerAlias(String name, String alias) {
		Assert.hasText(name, "'name' must not be empty");
		Assert.hasText(alias, "'alias' must not be empty");
		//如果别名与注册名相等
		if (alias.equals(name)) {
			//删除别名
			this.aliasMap.remove(alias);
		}
		else {
			//如果不允许别名被覆盖
			if (!allowAliasOverriding()) {
				String registeredName = this.aliasMap.get(alias);
				if (registeredName != null && !registeredName.equals(name)) {
					throw new IllegalStateException("Cannot register alias '" + alias + "' for name '" +
							name + "': It is already registered for name '" + registeredName + "'.");
				}
			}
			//检查是否有别名环的存在
			checkForAliasCircle(name, alias);
			// 注册别名的实际步骤
			this.aliasMap.put(alias, name);
		}
	}

	/**
	 * Return whether alias overriding is allowed.
	 * Default is {@code true}.
	 */
	// 用于判断别名是否可以被覆盖，当前返回true可以被子类实现重写
	protected boolean allowAliasOverriding() {
		return true;
	}

	// 删除别名
	public void removeAlias(String alias) {
		String name = this.aliasMap.remove(alias);
		if (name == null) {
			throw new IllegalStateException("No alias '" + alias + "' registered");
		}
	}

	//判断是否是别名
	public boolean isAlias(String name) {
		return this.aliasMap.containsKey(name);
	}

	// 获得当前Bean注册的所有别名
	public String[] getAliases(String name) {
		List<String> result = new ArrayList<String>();
		synchronized (this.aliasMap) {
			//实际获得逻辑
			retrieveAliases(name, result);
		}
		return StringUtils.toStringArray(result);
	}

	/**
	 * Transitively retrieve all aliases for the given name.
	 * @param name the target name to find aliases for
	 * @param result the resulting aliases list
	 */
	// 实际获得Bean注册的别名逻辑
	private void retrieveAliases(String name, List<String> result) {
		// 遍历所有Entry
		for (Map.Entry<String, String> entry : this.aliasMap.entrySet()) {
			String registeredName = entry.getValue();
			if (registeredName.equals(name)) {
				String alias = entry.getKey();
				result.add(alias);
				//*递归调用寻找多级别名关系
				retrieveAliases(alias, result);
			}
		}
	}

	/**
	 * Resolve all alias target names and aliases registered in this
	 * factory, applying the given StringValueResolver to them.
	 * <p>The value resolver may for example resolve placeholders
	 * in target bean names and even in alias names.
	 * @param valueResolver the StringValueResolver to apply
	 */

	//使用StringValueResolver过滤别名mapping关系
	public void resolveAliases(StringValueResolver valueResolver) {
		Assert.notNull(valueResolver, "StringValueResolver must not be null");
		synchronized (this.aliasMap) {
			// 复制当前别名mapping关系防止删除数据导致混乱
			Map<String, String> aliasCopy = new HashMap<String, String>(this.aliasMap);
			for (String alias : aliasCopy.keySet()) {
				String registeredName = aliasCopy.get(alias);
				//过滤名称
				String resolvedAlias = valueResolver.resolveStringValue(alias);
				String resolvedName = valueResolver.resolveStringValue(registeredName);
				if (resolvedAlias.equals(resolvedName)) {
					//如果过滤后别名与映射名称相等则删除别名
					this.aliasMap.remove(alias);
				}
				else if (!resolvedAlias.equals(alias)) {
					// 如果过滤后的别名与现有别名不相等则判断过滤后别名是否有映射关系
					String existingName = this.aliasMap.get(resolvedAlias);
					// 有则抛出异常
					if (existingName != null && !existingName.equals(resolvedName)) {
						throw new IllegalStateException(
								"Cannot register resolved alias '" + resolvedAlias + "' (original: '" + alias +
								"') for name '" + resolvedName + "': It is already registered for name '" +
								registeredName + "'.");
					}
					// 没有则判断循环并添加映射关系
					checkForAliasCircle(resolvedName, resolvedAlias);
					this.aliasMap.remove(alias);
					this.aliasMap.put(resolvedAlias, resolvedName);
				}
				else if (!registeredName.equals(resolvedName)) {
					this.aliasMap.put(alias, resolvedName);
				}
			}
		}
	}

	/**
	 * Determine the raw name, resolving aliases to canonical names.
	 * @param name the user-specified name
	 * @return the transformed name
	 */
	//根据别名查找根名称
	public String canonicalName(String name) {
		String canonicalName = name;
		// Handle aliasing...
		String resolvedName;
		do {
			resolvedName = this.aliasMap.get(canonicalName);
			if (resolvedName != null) {
				canonicalName = resolvedName;
			}
		}
		while (resolvedName != null);
		return canonicalName;
	}

	/**
	 * Check whether the given name points back to given alias as an alias
	 * in the other direction, catching a circular reference upfront and
	 * throwing a corresponding IllegalStateException.
	 * @param name the candidate name
	 * @param alias the candidate alias
	 * @see #registerAlias
	 */
	// 判断是否存在别名环如果根别名与要注册的别名相等则抛出异常
	protected void checkForAliasCircle(String name, String alias) {
		if (alias.equals(canonicalName(name))) {
			throw new IllegalStateException("Cannot register alias '" + alias +
					"' for name '" + name + "': Circular reference - '" +
					name + "' is a direct or indirect alias for '" + alias + "' already");
		}
	}

}
