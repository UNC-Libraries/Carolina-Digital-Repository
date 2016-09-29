package edu.unc.lib.dl.rdf; 

import static com.hp.hpl.jena.rdf.model.ResourceFactory.createProperty;
import static com.hp.hpl.jena.rdf.model.ResourceFactory.createResource;

import com.hp.hpl.jena.rdf.model.Property;
import com.hp.hpl.jena.rdf.model.Resource;
 
/**
 * Vocabulary definitions from /Users/bbpennel/Desktop/cdr-schemas/cdrAcl.rdf 
 * @author Auto-generated by schemagen on 05 May 2016 10:57 
 */
public class CdrAcl {
    
    /** The namespace of the vocabulary as a string */
    public static final String NS = "http://cdr.unc.edu/definitions/acl#";
    
    /** The namespace of the vocabulary as a string
     *  @see #NS */
    public static String getURI() {return NS;}
    
    /** The namespace of the vocabulary as a resource */
    public static final Resource NAMESPACE = createResource( NS );
    
    /** Grants the specified group or user permission to download binaries from children 
     *  of this object. Also has all the rights granted by the canDiscover role. Applies 
     *  to cdr:Collection objects. Repeatable.
     */
    public static final Property canAccess = createProperty( "http://cdr.unc.edu/definitions/acl#canAccess" );
    
    /** Grants the specified group or user permission to add or edit descriptive records 
     *  for this object and all of its children. Also has all of the rights granted 
     *  by the canAccess role. Applies to cdr:Collection and cdr:AdminUnit objects. 
     *  Repeatable.
     */
    public static final Property canDescribe = createProperty( "http://cdr.unc.edu/definitions/acl#canDescribe" );
    
    /** Grants the specified group or user permission to view metadata records for 
     *  this object and all of its children. Applies to cdr:Collection objects. Repeatable.
     */
    public static final Property canDiscover = createProperty( "http://cdr.unc.edu/definitions/acl#canDiscover" );
    
    /** Grants the specified group or user permission ingest new objects into this 
     *  object and any children containers. Also has all of the rights granted by 
     *  the canDescribe role. Applies to cdr:Collection and cdr:AdminUnit objects. 
     *  Repeatable.
     */
    public static final Property canIngest = createProperty( "http://cdr.unc.edu/definitions/acl#canIngest" );
    
    /** Grants the specified group or user permission to move objects, change access 
     *  to objects, or mark objects for deletion all children objects into this object 
     *  and any children containers. Also has all of the rights granted by the canIngest 
     *  role. Applies to cdr:Collection and cdr:AdminUnit objects. Repeatable.
     */
    public static final Property canManage = createProperty( "http://cdr.unc.edu/definitions/acl#canManage" );
    
    /** A restriction on patron access which expires after date given. When in effect, 
     *  the embargo reduces patron access to only being able to view metadata for 
     *  the object and all objects contained within it. Applies to cdr:FileObject, 
     *  cdr:AggregateWork, cdr:Folder and cdr:Collection.
     */
    public static final Property embargoUntil = createProperty( "http://cdr.unc.edu/definitions/acl#embargoUntil" );
    
    /** Indicates that the object has been selected for deletion from the repository, 
     *  and removes patron access. Applies to cdr:FileObject, cdr:AggregateWork, and 
     *  cdr:Folder objects.
     */
    public static final Property markedForDeletion = createProperty( "http://cdr.unc.edu/definitions/acl#markedForDeletion" );
    
    /** Indicates the user who has ownership of this object, and would be able to 
     *  grant temporary access permissions to it. Applies to cdr:FileObject, cdr:Aggregate 
     *  and cdr:Folder objects.
     */
    public static final Property owner = createProperty( "http://cdr.unc.edu/definitions/acl#owner" );
    
    /** Grants the specified category of users patron access to this object and its 
     *  children, unless further restricted by the child. Cannot exceed the level 
     *  of patron access granted on a parent object. Valid values are: "everyone", 
     *  "authenticated", "none", "parent". Applies to cdr:FileObject, cdr:AggregateWork, 
     *  cdr:Folder, and cdr:Collection objects.
     */
    public static final Property patronAccess = createProperty( "http://cdr.unc.edu/definitions/acl#patronAccess" );
    
    /** If true, restricts users with patron access to only be able to view metadata 
     *  records for this object and its children. Applies to cdr:Collection objects.
     */
    public static final Property patronsMetadataOnly = createProperty( "http://cdr.unc.edu/definitions/acl#patronsMetadataOnly" );
    
    /** User granted ownership of an administrative unit. Has all access and administrative 
     *  permissions, as well as permission to permanently destroy objects, create 
     *  collections, and assign staff permissions. Applies to cdr:AdminUnit objects. 
     *  Repeatable.
     */
    public static final Property unitOwner = createProperty( "http://cdr.unc.edu/definitions/acl#unitOwner" );
    
}
