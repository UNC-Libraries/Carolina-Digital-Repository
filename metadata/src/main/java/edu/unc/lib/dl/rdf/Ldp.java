package edu.unc.lib.dl.rdf;

import static org.apache.jena.rdf.model.ResourceFactory.createProperty;
import static org.apache.jena.rdf.model.ResourceFactory.createResource;

import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.Resource;
 
/**
 * Vocabulary definitions from rdf-schemas/ldp.ttl 
 * @author Auto-generated by schemagen on 23 May 2016 17:16 
 */
public class Ldp {
    
    /** The namespace of the vocabulary as a string */
    public static final String NS = "http://www.w3.org/ns/ldp#";
    
    /** The namespace of the vocabulary as a string
     *  @see #NS */
    public static String getURI() {return NS;}
    
    /** The namespace of the vocabulary as a resource */
    public static final Resource NAMESPACE = createResource( NS );
    
    /** Links a resource with constraints that the server requires requests like creation 
     *  and update to conform to.
     */
    public static final Property constrainedBy = createProperty( "http://www.w3.org/ns/ldp#constrainedBy" );
    
    /** Links a container with resources created through the container. */
    public static final Property contains = createProperty( "http://www.w3.org/ns/ldp#contains" );
    
    /** Indicates which predicate is used in membership triples, and that the membership 
     *  triple pattern is &lt; membership-constant-URI , object-of-hasMemberRelation, 
     *  member-URI &gt;.
     */
    public static final Property hasMemberRelation = createProperty( "http://www.w3.org/ns/ldp#hasMemberRelation" );
    
    /** Indicates which triple in a creation request should be used as the member-URI 
     *  value in the membership triple added when the creation request is successful.
     */
    public static final Property insertedContentRelation = createProperty( "http://www.w3.org/ns/ldp#insertedContentRelation" );
    
    /** Indicates which predicate is used in membership triples, and that the membership 
     *  triple pattern is &lt; member-URI , object-of-isMemberOfRelation, membership-constant-URI 
     *  &gt;.
     */
    public static final Property isMemberOfRelation = createProperty( "http://www.w3.org/ns/ldp#isMemberOfRelation" );
    
    /** LDP servers should use this predicate as the membership predicate if there 
     *  is no obvious predicate from an application vocabulary to use.
     */
    public static final Property member = createProperty( "http://www.w3.org/ns/ldp#member" );
    
    /** Indicates the membership-constant-URI in a membership triple. Depending upon 
     *  the membership triple pattern a container uses, as indicated by the presence 
     *  of ldp:hasMemberRelation or ldp:isMemberOfRelation, the membership-constant-URI 
     *  might occupy either the subject or object position in membership triples.
     */
    public static final Property membershipResource = createProperty( "http://www.w3.org/ns/ldp#membershipResource" );
    
}
