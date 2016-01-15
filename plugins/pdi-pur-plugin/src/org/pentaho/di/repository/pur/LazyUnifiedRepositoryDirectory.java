/*!
 * Copyright 2016 Pentaho Corporation.  All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.pentaho.di.repository.pur;

import org.apache.commons.lang.StringUtils;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.di.repository.ObjectId;
import org.pentaho.di.repository.RepositoryDirectory;
import org.pentaho.di.repository.RepositoryDirectoryInterface;
import org.pentaho.di.repository.RepositoryElementMetaInterface;
import org.pentaho.di.repository.RepositoryObjectType;
import org.pentaho.di.repository.StringObjectId;
import org.pentaho.di.repository.pur.model.EERepositoryObject;
import org.pentaho.di.repository.pur.model.RepositoryLock;
import org.pentaho.di.ui.repository.pur.services.ILockService;
import org.pentaho.platform.api.repository2.unified.IUnifiedRepository;
import org.pentaho.platform.api.repository2.unified.RepositoryFile;
import org.pentaho.platform.api.repository2.unified.RepositoryRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static org.pentaho.di.repository.pur.PurRepository.getObjectType;

/**
 * A version of RepositoryDirectoryInterface which only loads from the underlying repository as needed (Lazy)
 * <p/>
 * Created by nbaker on 12/22/15.
 * <p/>
 * Note the only reason we're extending RepositoryDirectory instead of implementing RepositoryDirectoryInterface is due
 * to some interface methods returning RepositoryDirectory!!
 */
public class LazyUnifiedRepositoryDirectory extends RepositoryDirectory {

  private RepositoryFile self;
  private IUnifiedRepository repository;
  private RepositoryServiceRegistry registry;
  private List<RepositoryDirectoryInterface> subdirectories;
  private List<RepositoryElementMetaInterface> fileChildren;
  private RepositoryDirectoryInterface parent;
  private Logger logger = LoggerFactory.getLogger( getClass() );

  public LazyUnifiedRepositoryDirectory( RepositoryFile self, RepositoryDirectoryInterface parent,
                                         IUnifiedRepository repository, RepositoryServiceRegistry registry ) {
    this.self = self;
    this.parent = parent;
    this.repository = repository;
    this.registry = registry;
  }


  @Override public RepositoryDirectory findDirectory( String path ) {
    if ( StringUtils.isEmpty( path ) ) {
      return null;
    }
    String absolutePath;
    if ( path.startsWith( RepositoryDirectory.DIRECTORY_SEPARATOR ) ) {
      if ( self.getPath().endsWith( RepositoryDirectory.DIRECTORY_SEPARATOR ) ) {
        absolutePath = self.getPath() + path.substring( 1 );
      } else {
        absolutePath = self.getPath() + path;
      }
    } else {
      if ( self.getPath().endsWith( RepositoryDirectory.DIRECTORY_SEPARATOR ) ) {
        absolutePath = self.getPath() + path;
      } else {
        absolutePath = self.getPath() + RepositoryDirectory.DIRECTORY_SEPARATOR + path;
      }
    }

    RepositoryFile file = repository.getFile( absolutePath );
    if ( file == null || !file.isFolder() ) {
      return null;
    }
    return new LazyUnifiedRepositoryDirectory( file, this, repository, registry );

  }

  @Override public RepositoryDirectory findChild( String name ) {
    return findDirectory( getPath() + RepositoryDirectory.DIRECTORY_SEPARATOR + name );
  }

  @Override public RepositoryDirectory findDirectory( String[] path ) {
    return findDirectory( StringUtils.join( path, "/" ) );
  }

  @Override public List<RepositoryDirectoryInterface> getChildren() {
    if ( subdirectories == null ) {
      subdirectories = new ArrayList<>();
      synchronized ( subdirectories ) {
        List<RepositoryFile> children = getAllURChildrenFiles();
        for ( RepositoryFile child : children ) {
          LazyUnifiedRepositoryDirectory dir = new LazyUnifiedRepositoryDirectory( child, this, repository, registry );
          dir.setObjectId( new StringObjectId( child.getId().toString() ) );
          this.addSubdirectory( dir );
        }
      }
    }
    return subdirectories;
  }

  @Override public List<RepositoryElementMetaInterface> getRepositoryObjects() {
    if ( fileChildren == null ) {

      fileChildren = new ArrayList<RepositoryElementMetaInterface>();
      synchronized ( fileChildren ) {

        UnifiedRepositoryLockService lockService =
            (UnifiedRepositoryLockService) registry.getService( ILockService.class );

        RepositoryRequest repositoryRequest = new RepositoryRequest();
        repositoryRequest.setTypes( RepositoryRequest.FILES_TYPE_FILTER.FILES );
        repositoryRequest.setPath( this.self.getId().toString() );
        List<RepositoryFile> children = repository.getChildren( repositoryRequest );
        for ( RepositoryFile child : children ) {

          RepositoryLock lock = null;
          try {
            lock = lockService.getLock( child );
            RepositoryObjectType objectType = getObjectType( child.getName() );
            EERepositoryObject repositoryObject =
                new EERepositoryObject( child, this, null, objectType, null, lock, false );

            // TODO: We have no access to this information outside of RepositoryFileTree


            repositoryObject.setVersioningEnabled( true );
            repositoryObject.setVersionCommentEnabled( true );
            fileChildren.add( repositoryObject );
          } catch ( KettleException e ) {
            logger.error( "Error converting Unified Repository file to PDI RepositoryObject: " + child.getPath()
                + ". File will be skipped", e );
          }
        }
      }
    }
    return fileChildren;

  }

  @Override public void setRepositoryObjects( List<RepositoryElementMetaInterface> list ) {
    synchronized ( fileChildren ) {
      fileChildren.clear();
      fileChildren.addAll( list );
    }
  }

  @Override public boolean isVisible() {
    return !self.isHidden();
  }


  @Override public int getNrSubdirectories() {
    RepositoryRequest repositoryRequest = new RepositoryRequest();
    repositoryRequest.setTypes( RepositoryRequest.FILES_TYPE_FILTER.FOLDERS );
    repositoryRequest.setPath( this.self.getId().toString() );
    List<RepositoryFile> children = repository.getChildren( repositoryRequest );
    Iterator<RepositoryFile> iterator = children.iterator();
    while ( iterator.hasNext() ) {
      RepositoryFile next = iterator.next();
      if ( !next.isFolder() ) {
        iterator.remove();
      }
    }
    return children.size();
  }

  @Override public RepositoryDirectory getSubdirectory( int i ) {
    List<RepositoryFile> children = getAllURChildrenFiles();
    if ( i > children.size() || i < 0 ) {
      return null;
    }

    RepositoryFile child = children.get( i );
    LazyUnifiedRepositoryDirectory dir = new LazyUnifiedRepositoryDirectory( child, this, repository, registry );
    dir.setObjectId( new StringObjectId( child.getId().toString() ) );
    this.addSubdirectory( dir );
    return dir;

  }

  private List<RepositoryFile> getAllURChildrenFiles() {
    RepositoryRequest repositoryRequest = new RepositoryRequest();
    repositoryRequest.setTypes( RepositoryRequest.FILES_TYPE_FILTER.FOLDERS );
    repositoryRequest.setPath( this.self.getId().toString() );
    List<RepositoryFile> children = repository.getChildren( repositoryRequest );
    // Filter for Folders only doesn't appear to work
    Iterator<RepositoryFile> iterator = children.iterator();
    while ( iterator.hasNext() ) {
      RepositoryFile next = iterator.next();
      if ( !next.isFolder() ) {
        iterator.remove();
      }
    }
    return children;
  }

  @Override public void clear() {
    if ( this.fileChildren != null ) {
      synchronized ( fileChildren ) {
        this.fileChildren.clear();
      }
    }
    if ( this.subdirectories != null ) {
      synchronized ( subdirectories ) {
        this.subdirectories.clear();
      }
    }
  }

  @Override public void addSubdirectory( RepositoryDirectoryInterface repositoryDirectoryInterface ) {
    if ( subdirectories == null ) {
      subdirectories = new ArrayList<>();
    }
    synchronized ( subdirectories ) {
      this.subdirectories.add( repositoryDirectoryInterface );
    }
  }

  @Override public String getName() {
    return self.getName();
  }

  @Override public String getPath() {
    return self.getPath();
  }

  @Override public ObjectId getObjectId() {
    return new StringObjectId( self.getId().toString() );
  }

  @Override public void setChildren( List<RepositoryDirectoryInterface> list ) {
    if ( subdirectories == null ) {
      subdirectories = new ArrayList<>();
    }
    synchronized ( subdirectories ) {
      subdirectories.clear();
      subdirectories.addAll( list );
    }
  }

  @Override public String[] getPathArray() {
    return getPath().split( RepositoryDirectory.DIRECTORY_SEPARATOR );
  }


  @Override public ObjectId[] getDirectoryIDs() {
    List<RepositoryFile> children = this.getAllURChildrenFiles();
    ObjectId[] objectIds = new ObjectId[ children.size() ];
    for ( int i = 0; i < children.size(); i++ ) {
      objectIds[ i ] = new StringObjectId( children.get( i ).getId().toString() );
    }

    return objectIds;
  }

  @Override public boolean isRoot() {
    return parent == null;
  }

  @Override public RepositoryDirectoryInterface findRoot() {
    RepositoryDirectoryInterface current = this;
    RepositoryDirectoryInterface parent = null;
    while ( ( parent = current.getParent() ) != null ) {
      current = parent;
    }
    return current;
  }

  @Override public void setParent( RepositoryDirectoryInterface repositoryDirectoryInterface ) {
    this.parent = repositoryDirectoryInterface;
  }

  @Override public RepositoryDirectoryInterface getParent() {
    return parent;
  }

  @Override public void setObjectId( ObjectId objectId ) {
    // ignore
  }

  @Override public void setName( String s ) {
    // ignore
  }


  @Override
  public String getPathObjectCombination( String transName ) {
    if ( isRoot() ) {
      return getPath() + transName;
    } else {
      return getPath() + RepositoryDirectory.DIRECTORY_SEPARATOR + transName;
    }
  }
}