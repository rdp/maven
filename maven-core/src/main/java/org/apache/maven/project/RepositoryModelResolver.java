package org.apache.maven.project;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolutionRequest;
import org.apache.maven.artifact.resolver.ArtifactResolutionResult;
import org.apache.maven.artifact.resolver.ResolutionErrorHandler;
import org.apache.maven.artifact.resolver.TransferListenerAdapter;
import org.apache.maven.model.Repository;
import org.apache.maven.model.building.FileModelSource;
import org.apache.maven.model.building.ModelSource;
import org.apache.maven.model.resolution.InvalidRepositoryException;
import org.apache.maven.model.resolution.ModelResolver;
import org.apache.maven.model.resolution.UnresolvableModelException;
import org.apache.maven.repository.RepositorySystem;

/**
 * Implements a model resolver backed by the Maven Repository API and the reactor.
 * 
 * @author Benjamin Bentmann
 */
class RepositoryModelResolver
    implements ModelResolver
{

    private RepositorySystem repositorySystem;

    private ResolutionErrorHandler resolutionErrorHandler;

    private ProjectBuildingRequest projectBuildingRequest;

    private List<ArtifactRepository> remoteRepositories;

    private ReactorModelPool reactorModelPool;

    public RepositoryModelResolver( RepositorySystem repositorySystem, ResolutionErrorHandler resolutionErrorHandler,
                                    ProjectBuildingRequest projectBuildingRequest, ReactorModelPool reactorModelPool )
    {
        if ( repositorySystem == null )
        {
            throw new IllegalArgumentException( "no repository system specified" );
        }
        this.repositorySystem = repositorySystem;

        if ( resolutionErrorHandler == null )
        {
            throw new IllegalArgumentException( "no resolution error handler specified" );
        }
        this.resolutionErrorHandler = resolutionErrorHandler;

        if ( projectBuildingRequest == null )
        {
            throw new IllegalArgumentException( "no project building request specified" );
        }
        this.projectBuildingRequest = projectBuildingRequest;

        if ( projectBuildingRequest.getRemoteRepositories() == null )
        {
            throw new IllegalArgumentException( "no remote repositories specified" );
        }
        this.remoteRepositories = new ArrayList<ArtifactRepository>( projectBuildingRequest.getRemoteRepositories() );

        this.reactorModelPool = reactorModelPool;
    }

    public ModelResolver newCopy()
    {
        return new RepositoryModelResolver( repositorySystem, resolutionErrorHandler, projectBuildingRequest,
                                            reactorModelPool );
    }

    public void addRepository( Repository repository )
        throws InvalidRepositoryException
    {
        try
        {
            ArtifactRepository repo = repositorySystem.buildArtifactRepository( repository );

            repositorySystem.injectMirror( Arrays.asList( repo ), projectBuildingRequest.getMirrors() );

            repositorySystem.injectProxy( Arrays.asList( repo ), projectBuildingRequest.getProxies() );

            repositorySystem.injectAuthentication( Arrays.asList( repo ), projectBuildingRequest.getServers() );

            remoteRepositories.add( 0, repo );

            remoteRepositories = repositorySystem.getEffectiveRepositories( remoteRepositories );
        }
        catch ( Exception e )
        {
            throw new InvalidRepositoryException( e.getMessage(), repository, e );
        }
    }

    public ModelSource resolveModel( String groupId, String artifactId, String version )
        throws UnresolvableModelException
    {
        File pomFile = null;

        if ( reactorModelPool != null )
        {
            pomFile = reactorModelPool.get( groupId, artifactId, version );
        }

        if ( pomFile == null )
        {
            Artifact artifactParent = repositorySystem.createProjectArtifact( groupId, artifactId, version );

            ArtifactResolutionRequest request = new ArtifactResolutionRequest();
            request.setArtifact( artifactParent );
            request.setRemoteRepositories( remoteRepositories );
            request.setLocalRepository( projectBuildingRequest.getLocalRepository() );
            request.setOffline( projectBuildingRequest.isOffline() );
            request.setCache( projectBuildingRequest.getRepositoryCache() );
            request.setTransferListener( TransferListenerAdapter.newAdapter( projectBuildingRequest.getTransferListener() ) );

            ArtifactResolutionResult result = repositorySystem.resolve( request );

            try
            {
                resolutionErrorHandler.throwErrors( request, result );
            }
            catch ( ArtifactResolutionException e )
            {
                throw new UnresolvableModelException( "Failed to resolve POM for " + groupId + ":" + artifactId + ":"
                    + version + " due to " + e.getMessage(), groupId, artifactId, version, e );
            }

            pomFile = artifactParent.getFile();
        }

        return new FileModelSource( pomFile );
    }

}
