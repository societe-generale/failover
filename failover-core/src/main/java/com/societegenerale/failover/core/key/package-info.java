/**
 * Key generation for uniquely identifying cached failover entries.
 *
 * <p>{@link com.societegenerale.failover.core.key.KeyGenerator} derives a string store key
 * from a method's arguments. The default implementation serialises the argument list to JSON.
 */
package com.societegenerale.failover.core.key;
