package util;

import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.BinaryOperator;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import sun.misc.Unsafe;

public class UnsafeReflectionUtils
{
	private static final Object IDENTITY_HASH_MAP_NULL_KEY;
	static
	{
		try
		{
			Field nullKeyF = IdentityHashMap.class.getDeclaredField("NULL_KEY");
			makeAccessible(nullKeyF);
			IDENTITY_HASH_MAP_NULL_KEY = nullKeyF.get(null);
		} catch(ReflectiveOperationException e)
		{
			throw new RuntimeException(e);
		}
	}

	/**
	 * Converts a map of objects to FieldPath to a map of the classes of the objects to FieldPaths.
	 * If there are multiple paths for objects with the same class, the shortest path is used.<br>
	 * Intended to be used on the return value of {@link #listReachableObjectsRecursively(Object, int, int)}.
	 */
	public static Map<Class<?>, FieldPath> extractReachableClasses(Map<Object, FieldPath> reachableObjects)
	{
		return reachableObjects
				.entrySet()
				.stream()
				.collect(Collectors.groupingBy(e -> e.getKey().getClass(),
						Collectors.mapping(e -> e.getValue(),
								Collectors.reducing(FieldPath.UNKNOWN_PATH,
										BinaryOperator.minBy(Comparator.comparing(e -> e))))));
	}

	/**
	 * Lists all objects which are reachable from the given root in at most {@code maxDepth steps}.
	 * Stops exploring when {@code maxObjects} objects have been found.<br>
	 * Each step is done by {@link #readAllNonprimitiveNonnullFieldsOrArrayComponents(Object)}.
	 */
	public static Map<Object, FieldPath> listReachableObjectsRecursively(Object root, int maxDepth, int maxObjects) throws ReflectiveOperationException
	{
		Map<Object, FieldPath> reachableObjects = new IdentityHashMap<>();
		Map<Object, FieldPath> objectsToSearch = new IdentityHashMap<>();

		reachableObjects.put(root, new FieldPath());
		objectsToSearch.put(root, new FieldPath());

		for(int depth = 0; depth < maxDepth; depth ++)
		{
			Map<Object, FieldPath> objectsToSearchNext = new IdentityHashMap<>();
			for(Entry<Object, FieldPath> objectWithPath : objectsToSearch.entrySet())
			{
				Object currentObject = objectWithPath.getKey();
				FieldPath currentObjectPath = objectWithPath.getValue();

				for(Entry<String, Object> fieldWithValue : readAllNonprimitiveNonnullFieldsOrArrayComponents(currentObject).entrySet())
				{
					Object reachableObject = fieldWithValue.getValue();
					if(reachableObject == null || reachableObjects.containsKey(reachableObject))
						continue;

					if(fieldWithValue.getValue() == IDENTITY_HASH_MAP_NULL_KEY)
						// This object represents null for IdentityHashMap, so we can't put this object
						// into reachableObjects, because when requesting it back from the map, we get null
						// because reachableObjects is itself a IdentityHashMap.
						// That's not a big problem; since this object is just a java.lang.Object, it isn't interesting anyway,
						// so we can just exclude it.
						continue;

					FieldPath reachableObjectPath = currentObjectPath.append(fieldWithValue.getKey());
					reachableObjects.put(reachableObject, reachableObjectPath);
					objectsToSearchNext.put(reachableObject, reachableObjectPath);

					if(maxObjects > 0 && reachableObjects.size() >= maxObjects)
						return reachableObjects;
				}
			}

			objectsToSearch = objectsToSearchNext;
		}

		return reachableObjects;
	}

	/**
	 * If the given object is a primitive array, returns an empty map.<br>
	 * If the given object is a nonprimitive array, returns a map of indices (as Strings) to values,
	 * for each index where the given array contains a non-null value.<br>
	 * Else, if the object is a regular object, returns the values of all fields by using {@link #readAllNonprimitiveNonnullFields(Object)}.
	 */
	public static Map<String, Object> readAllNonprimitiveNonnullFieldsOrArrayComponents(Object object) throws ReflectiveOperationException
	{
		if(object.getClass().isArray())
		{
			if(object.getClass().componentType().isPrimitive())
				return Map.of();

			record ArrayComponent(String index, Object value)
			{}
			return IntStream
					.range(0, Array.getLength(object))
					.mapToObj(i -> new ArrayComponent(Integer.toString(i), Array.get(object, i)))
					.filter(c -> c.value() != null)
					.collect(Collectors.toMap(ArrayComponent::index, ArrayComponent::value));
		}

		return readAllNonprimitiveNonnullFields(object)
				.entrySet()
				.stream()
				.collect(Collectors.toMap(e -> e.getKey().getName(), Entry::getValue, (v1, v2) ->
				{
					if(v1 == v2)
						return v1;

					System.err.println("WARNING: Found two fields with same name with different values; one of the values will not be reported: "
							+ v1 + ", " + v2);
					return v1;
				}));
	}

	/**
	 * Reads all fields (including static fields) of the given object, including fields declared by superclasses,
	 * bypassing all access restrictions.
	 * Fields with primitive types and fields with {@code null} as their value are excluded.<br>
	 * Does not work if the given object is an array;
	 * for objects which could be arrays, use {@link #readAllNonprimitiveNonnullFieldsOrArrayComponents(Object)} instead.
	 */
	public static Map<Field, Object> readAllNonprimitiveNonnullFields(Object object) throws ReflectiveOperationException
	{
		Map<Field, Object> result = new HashMap<>();

		for(Class<?> clazz = object.getClass(); clazz != null; clazz = clazz.getSuperclass())
			for(Field field : getAllDeclaredFields(clazz))
			{
				if(field.getType().isPrimitive())
					continue;

				if(field.equals(Class.class.getDeclaredField("componentType")) && !((Class<?>) object).isArray())
					// This field is declared as Class, but sometimes doesn't contain a Class for non-array classes.
					continue;

				makeAccessible(field);
				Object value = field.get(object);
				if(value != null)
					result.put(field, value);
			}

		return result;
	}

	/**
	 * Sets the given path of fields starting from the given root class to the given value, bypassing all access right restrictions.
	 * Returns the old value.<br>
	 * The first step is done by {@link #readStaticField(Class, String)}; the rest is done by {@link #writePath(Object, String...)}.
	 */
	public static Object writePathStartingWithStatic(Class<?> rootClass, Object value, String firstFieldName, String... path) throws ReflectiveOperationException
	{
		return writePath(readStaticField(rootClass, firstFieldName), path);
	}

	/**
	 * Sets the given path of fields starting from the given root object to the given value, bypassing all access right restrictions.
	 * Returns the old value.<br>
	 * Each step until the last is done by {@link #readField(Object, String)}; the last step is done by {@link #writeField(Object, Object, String)}.
	 */
	public static Object writePath(Object root, Object value, String... path) throws ReflectiveOperationException
	{
		Object obj = root;
		for(int i = 0; i < path.length - 1; i ++)
			obj = readField(obj, path[i]);
		return writeField(obj, value, path[path.length - 1]);
	}

	/**
	 * Finds the class's (static) field with the given name using {@link #findField(Class, String)} and sets it to the given value,
	 * bypassing all access right restrictions. Returns the old value.<br>
	 */
	public static Object writeStaticField(Class<?> clazz, Object value, String fieldName) throws ReflectiveOperationException
	{
		Field field = findField(clazz, fieldName);
		Object result = field.get(null);
		field.set(null, value);
		return result;
	}

	/**
	 * If the given object is an array, sets the i-th component to the given value, where i is the given name interpreted as an integer.<br>
	 * Else, finds the object's field with the given name using {@link #findField(Object, String)} and sets it to the given value,
	 * bypassing all access right restrictions (by using {@link #makeAccessible(AccessibleObject)}).<br>
	 * In either case, the old value is returned.
	 */
	public static Object writeField(Object object, Object value, String fieldName) throws ReflectiveOperationException
	{
		if(object.getClass().isArray())
		{
			int index = Integer.parseInt(fieldName);
			Object result = Array.get(object, index);
			Array.set(object, index, value);
			return result;
		}
		Field field = findField(object, fieldName);
		Object result = field.get(object);
		field.set(object, value);
		return result;
	}

	/**
	 * Reads the given path of fields starting from the given root class, bypassing all access right restrictions.
	 * The first step is read by {@link #readStaticField(Class, String)}; the rest is read by {@link #readPath(Object, String...)}.
	 */
	public static Object readPathStartingWithStatic(Class<?> rootClass, String firstFieldName, String... path) throws ReflectiveOperationException
	{
		return readPath(readStaticField(rootClass, firstFieldName), path);
	}

	/**
	 * Reads the given path of fields starting from the given root object, bypassing all access right restrictions.
	 * Each step is read by {@link #readField(Object, String)}.
	 */
	public static Object readPath(Object root, String... path) throws ReflectiveOperationException
	{
		Object obj = root;
		for(int i = 0; i < path.length; i ++)
			obj = readField(obj, path[i]);
		return obj;
	}

	/**
	 * Finds the class's (static) field with the given name using {@link #findField(Class, String)} and reads its value,
	 * bypassing all access right restrictions.
	 */
	public static Object readStaticField(Class<?> clazz, String fieldName) throws ReflectiveOperationException
	{
		return findField(clazz, fieldName).get(null);
	}

	/**
	 * If the given object is an array, returns the i-th value, where i is the given name interpreted as an integer.<br>
	 * Else, finds the object's field with the given name using {@link #findField(Object, String)} and reads its value,
	 * bypassing all access right restrictions (by using {@link #makeAccessible(AccessibleObject)}).
	 */
	public static Object readField(Object object, String fieldName) throws ReflectiveOperationException
	{
		if(object.getClass().isArray())
			return Array.get(object, Integer.parseInt(fieldName));
		return findField(object, fieldName).get(object);
	}

	/**
	 * Finds the method with the given signature of the class of the given object and makes it accessible
	 * using {@link #findConstructor(Class, Class...)}
	 */
	@SuppressWarnings("unchecked")
	public static <T> Constructor<? extends T> findConstructor(T object, Class<?>... parameters) throws ReflectiveOperationException
	{
		return findConstructor((Class<? extends T>) object.getClass(), parameters);
	}

	/**
	 * Finds the constructor with the given signature of the given class and makes it accessible using {@link #makeAccessible(AccessibleObject)}.<br>
	 * The access restrictions imposed by {@link jdk.internal.reflect.Reflection} are irrelevant here because that class doesn't restrict constructors.
	 */
	public static <T> Constructor<T> findConstructor(Class<T> clazz, Class<?>... parameters) throws ReflectiveOperationException
	{
		for(Constructor<T> declaredConstructor : getAllDeclaredConstructors(clazz))
			if(Arrays.equals(declaredConstructor.getParameterTypes(), parameters))
			{
				makeAccessible(declaredConstructor);
				return declaredConstructor;
			}

		throw new NoSuchMethodException("(" + Arrays.toString(parameters) + ")");
	}

	/**
	 * Finds the method with the given signature of the class of the given object and makes it accessible
	 * using {@link #findMethod(Class, String, Class...)}
	 */
	public static Method findMethod(Object object, String methodName, Class<?>... parameters) throws ReflectiveOperationException
	{
		return findMethod(object.getClass(), methodName, parameters);
	}

	/**
	 * Finds the method with the given signature of the given class and makes it accessible using {@link #makeAccessible(AccessibleObject)}.
	 * Superclasses are searched as well. If there are multiple methods with the given signature, the one from the lowest class will be returned.<br>
	 * This method bypasses the access restrictions imposed by {@link jdk.internal.reflect.Reflection} by using {@link #getAllDeclaredMethods(Class)}.
	 */
	public static Method findMethod(Class<?> clazz, String methodName, Class<?>... parameters) throws ReflectiveOperationException
	{
		for(Class<?> currentClass = clazz; currentClass != null; currentClass = currentClass.getSuperclass())
			for(Method declaredMethod : getAllDeclaredMethods(currentClass))
				if(declaredMethod.getName().equals(methodName) && Arrays.equals(declaredMethod.getParameterTypes(), parameters))
				{
					makeAccessible(declaredMethod);
					return declaredMethod;
				}

		throw new NoSuchMethodException(methodName + "(" + Arrays.toString(parameters) + ")");
	}

	/**
	 * Finds the field with the given name of the class of the given object using {@link #findField(Class, String)}
	 */
	public static Field findField(Object object, String fieldName) throws ReflectiveOperationException
	{
		return findField(object.getClass(), fieldName);
	}

	/**
	 * Finds the field with the given name of the given class and makes it accessible using {@link #makeAccessible(AccessibleObject)}.
	 * Superclasses are searched as well. If there are multiple fields with the given name, the one from the lowest class will be returned.<br>
	 * This method bypasses the access restrictions imposed by {@link jdk.internal.reflect.Reflection} by using {@link #getAllDeclaredFields(Class)}.
	 */
	public static Field findField(Class<?> clazz, String fieldName) throws ReflectiveOperationException
	{
		for(Class<?> currentClass = clazz; currentClass != null; currentClass = currentClass.getSuperclass())
			for(Field declaredField : getAllDeclaredFields(currentClass))
				if(declaredField.getName().equals(fieldName))
				{
					makeAccessible(declaredField);
					return declaredField;
				}

		throw new NoSuchFieldException(fieldName);
	}

	/**
	 * Lists all constructors declared by the given class.<br>
	 * The access restrictions imposed by {@link jdk.internal.reflect.Reflection} are irrelevant here because that class doesn't restrict constructors.
	 */
	@SuppressWarnings("unchecked")
	public static <T> Constructor<T>[] getAllDeclaredConstructors(Class<T> clazz) throws ReflectiveOperationException
	{
		return (Constructor<T>[]) findMethod(Class.class, "getDeclaredConstructors0", boolean.class).invoke(clazz, false);
	}

	/**
	 * Lists all methods declared by the given class. This does not include methods of superclasses.<br>
	 * This method bypasses the access restrictions imposed by {@link jdk.internal.reflect.Reflection}.
	 */
	public static Method[] getAllDeclaredMethods(Class<?> currentClass) throws ReflectiveOperationException
	{
		// Can't use findMethod here since findMethod uses us.
		Method getDeclaredMethods0M = Class.class.getDeclaredMethod("getDeclaredMethods0", boolean.class);
		makeAccessible(getDeclaredMethods0M);
		return (Method[]) getDeclaredMethods0M.invoke(currentClass, false);
	}

	/**
	 * Lists all fields declared by the given class. This does not include fields of superclasses.<br>
	 * This method bypasses the access restrictions imposed by {@link jdk.internal.reflect.Reflection}.
	 */
	public static Field[] getAllDeclaredFields(Class<?> currentClass) throws ReflectiveOperationException
	{
		return (Field[]) findMethod(Class.class, "getDeclaredFields0", boolean.class).invoke(currentClass, false);
	}

	public static record FieldPath(List<String> path) implements Comparable<FieldPath>
	{
		public static final FieldPath UNKNOWN_PATH = new FieldPath(null);

		public FieldPath()
		{
			this(List.of());
		}
		public FieldPath(List<String> path)
		{
			if(UNKNOWN_PATH == null && path == null)
				this.path = null;
			else
				this.path = List.copyOf(path);
		}

		public FieldPath append(Field field)
		{
			return append(field.getName());
		}
		public FieldPath append(int arrayIndex)
		{
			return append(Integer.toString(arrayIndex));
		}
		public FieldPath append(String field)
		{
			List<String> appendedPath = new ArrayList<>(path());
			appendedPath.add(field);
			return new FieldPath(appendedPath);
		}

		@Override
		public int compareTo(FieldPath other)
		{
			if(this.path() == null)
				return other.path() == null ? 0 : 1;

			if(other.path() == null)
				return -1;

			return Integer.compare(this.path().size(), other.path().size());
		}

		@Override
		public String toString()
		{
			return path() == null ? "<unknown>" : path().stream().collect(Collectors.joining(" -> "));
		}
	}

	/**
	 * Does ugly magic with {@link Unsafe} to make the given {@link AccessibleObject} accessible
	 * bypassing all access right restrictions.
	 * This method works even if {@link AccessibleObject#setAccessible(boolean)} is not allowed.
	 */
	public static void makeAccessible(AccessibleObject accessibleObject) throws ReflectiveOperationException
	{
		long overrideOffset = getOverrideFieldOffset();
		Method putBooleanM = Unsafe.class.getDeclaredMethod("putBoolean", Object.class, long.class, boolean.class);
		putBooleanM.setAccessible(true);
		putBooleanM.invoke(getSunMiscUnsafe(), accessibleObject, overrideOffset, true);
	}

	private static long getOverrideFieldOffset() throws ReflectiveOperationException
	{
		Method objectFieldOffsetM = Unsafe.class.getDeclaredMethod("objectFieldOffset", Field.class);
		Field overrideMockF = AccessibleObjectMock.class.getDeclaredField("override");
		return (Long) objectFieldOffsetM.invoke(getSunMiscUnsafe(), overrideMockF);
	}

	/**
	 * Returns the JVM's instance of {@link Unsafe}.
	 */
	public static Unsafe getSunMiscUnsafe() throws ReflectiveOperationException
	{
		Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
		theUnsafe.setAccessible(true);
		return (Unsafe) theUnsafe.get(null);
	}

	private static class AccessibleObjectMock
	{
		// This assumes the real override field is the first one.
		@SuppressWarnings("unused")
		private boolean override;
	}

	private UnsafeReflectionUtils()
	{}
}
